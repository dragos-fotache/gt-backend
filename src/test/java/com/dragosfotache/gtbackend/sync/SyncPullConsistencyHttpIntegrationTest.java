package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncPullConsistencyHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "pull-consistency-audience";
    private static final AtomicReference<PublicationGate> PUBLICATION_GATE = new AtomicReference<>();
    private static final AtomicReference<RegistrationGate> REGISTRATION_GATE = new AtomicReference<>();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort int port;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;

    private record Account(UUID id, String token) {}
    private record Reply(int status, byte[] bytes) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        JsonNode json() {
            try { return JSON.readTree(bytes); }
            catch (Exception e) { throw new AssertionError(text(), e); }
        }
    }

    private static final class RegistrationGate {
        final CountDownLatch inserted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterInsert() {
            inserted.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("test did not release cursor registration");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("cursor registration gate interrupted", e);
            }
        }
    }

    private static final class PublicationGate {
        final CountDownLatch firstChangeInserted = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterInsert() {
            firstChangeInserted.countDown();
            try {
                if (!releaseWriter.await(15, TimeUnit.SECONDS)) throw new AssertionError("test did not release gated publisher");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("publication gate interrupted", e);
            }
        }
    }

    @BeforeEach
    void installPublicationGate() {
        PUBLICATION_GATE.set(null);
        REGISTRATION_GATE.set(null);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            PublicationGate gate = PUBLICATION_GATE.get();
            if (gate != null && sql.toLowerCase(java.util.Locale.ROOT).contains("insert into sync_changes")
                    && gate.used.compareAndSet(false, true)) gate.afterInsert();
            return result;
        }).when(jdbc).update(anyString(), any(Object[].class));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            RegistrationGate gate = REGISTRATION_GATE.get();
            if (gate != null && sql.toLowerCase(java.util.Locale.ROOT).startsWith("insert into sync_cursor_tokens")
                    && gate.used.compareAndSet(false, true)) gate.afterInsert();
            return result;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private Account account() {
        UUID id = UUID.randomUUID(), sessionId = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", id);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", sessionId, id);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '1 hour')", crypto.digest(token), sessionId);
        return new Account(id, token);
    }

    private String id() { return UUID.randomUUID().toString(); }

    private Reply request(int targetPort, String token, String method, String path, String body, String idempotencyKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                    .header("Authorization", "Bearer " + token);
            if (body != null) builder.header("Content-Type", "application/json");
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    private Reply get(Account account, String cursor, int limit) {
        String suffix = cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&limit=" + limit;
        if (cursor == null) suffix = "?limit=" + limit;
        return request(port, account.token(), "GET", "/v1/sync/pull" + suffix, null, null);
    }

    private Reply startWorkout(int targetPort, Account account, String id, String name) {
        return request(targetPort, account.token(), "POST", "/v1/workouts", "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"startedAt\":\"2025-02-01T00:00:00Z\"}", UUID.randomUUID().toString());
    }

    private Reply sendRaw(Account account, String path, String body, String key) {
        return request(port, account.token(), "POST", path, body, key);
    }

    private String state(UUID owner) {
        String account = owner.toString();
        String sql = "select jsonb_build_object(" +
                "'workouts',(select jsonb_agg(to_jsonb(w) order by w.id) from workouts w where w.owner_id='" + account + "'::uuid)," +
                "'workoutEntries',(select jsonb_agg(to_jsonb(e) order by e.workout_id,e.position) from workout_exercises e join workouts w on w.id=e.workout_id where w.owner_id='" + account + "'::uuid)," +
                "'workoutSets',(select jsonb_agg(to_jsonb(s) order by e.workout_id,e.position,s.position) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id join workouts w on w.id=e.workout_id where w.owner_id='" + account + "'::uuid)," +
                "'routines',(select jsonb_agg(to_jsonb(r) order by r.id) from routines r where r.owner_id='" + account + "'::uuid)," +
                "'routineEntries',(select jsonb_agg(to_jsonb(e) order by e.routine_id,e.position) from routine_exercises e join routines r on r.id=e.routine_id where r.owner_id='" + account + "'::uuid)," +
                "'routineSets',(select jsonb_agg(to_jsonb(s) order by e.routine_id,e.position,s.position) from routine_sets s join routine_exercises e on e.id=s.routine_exercise_id join routines r on r.id=e.routine_id where r.owner_id='" + account + "'::uuid)," +
                "'exercises',(select jsonb_agg(to_jsonb(e) order by e.id) from exercises e where e.owner_id='" + account + "'::uuid)," +
                "'state',(select to_jsonb(s) from account_sync_state s where s.account_id='" + account + "'::uuid)," +
                "'batches',(select jsonb_agg(to_jsonb(b) order by b.cursor) from sync_change_batches b where b.account_id='" + account + "'::uuid)," +
                "'changes',(select jsonb_agg(to_jsonb(c) order by c.cursor,c.position) from sync_changes c where c.account_id='" + account + "'::uuid)," +
                "'resources',(select jsonb_agg(to_jsonb(m) order by m.idempotency_key) from resource_mutations m where m.owner_id='" + account + "'::uuid)," +
                "'syncEvidence',(select jsonb_agg(to_jsonb(m) order by m.mutation_id) from processed_mutations m where m.owner_id='" + account + "'::uuid)," +
                "'rootReservations',(select jsonb_agg(to_jsonb(r) order by r.aggregate_type,r.aggregate_id) from root_id_reservations r where r.owner_id='" + account + "'::uuid)," +
                "'childReservations',(select jsonb_agg(to_jsonb(r) order by r.family,r.child_id) from child_id_reservations r where r.owner_id='" + account + "'::uuid)," +
                "'credentials',(select jsonb_agg(jsonb_build_object('fingerprint',encode(c.fingerprint,'hex'),'expires',c.expires_at,'revoked',s.revoked_at) order by c.fingerprint) from auth_credentials c join auth_sessions s on s.id=c.session_id where s.account_id='" + account + "'::uuid))::text";
        return jdbc.queryForObject(sql, String.class);
    }

    @Test
    void pullDuringRealHttpPublicationSeesWholeCommittedViewThenWholeMultiChangeBatch() throws Exception {
        Account owner = account();
        String oldActive = id();
        assertEquals(201, startWorkout(port, owner, oldActive, "old active").status());
        Reply prior = get(owner, null, 100);
        assertEquals(200, prior.status(), prior.text());
        String beforeCursor = prior.json().path("nextCursor").asText();
        assertEquals(1, prior.json().path("batches").size());

        PublicationGate gate = new PublicationGate();
        PUBLICATION_GATE.set(gate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String newActive = id();
            var writer = executor.submit(() -> startWorkout(port, owner, newActive, "new active"));
            assertTrue(gate.firstChangeInserted.await(10, TimeUnit.SECONDS), "publisher must insert first change while its account transaction remains open");

            Reply committedView = get(owner, beforeCursor, 100);
            assertEquals(200, committedView.status(), committedView.text());
            assertTrue(committedView.json().path("batches").isEmpty(), "uncommitted cursor and first change are invisible together");
            assertFalse(committedView.json().path("hasMore").asBoolean());
            assertEquals(beforeCursor, committedView.json().path("nextCursor").asText());

            gate.releaseWriter.countDown();
            Reply accepted = writer.get(15, TimeUnit.SECONDS);
            assertEquals(201, accepted.status(), accepted.text());
            Reply committedBatch = get(owner, beforeCursor, 100);
            assertEquals(200, committedBatch.status(), committedBatch.text());
            assertEquals(1, committedBatch.json().path("batches").size());
            JsonNode batch = committedBatch.json().path("batches").get(0);
            assertEquals(2, batch.path("changes").size());
            assertEquals(oldActive, batch.path("changes").get(0).path("aggregateId").asText());
            assertEquals("delete", batch.path("changes").get(0).path("operation").asText());
            assertTrue(batch.path("changes").get(0).path("record").isMissingNode() || batch.path("changes").get(0).path("record").isNull());
            assertEquals(newActive, batch.path("changes").get(1).path("aggregateId").asText());
            assertEquals("upsert", batch.path("changes").get(1).path("operation").asText());
            assertEquals(batch.path("cursor").asText(), committedBatch.json().path("nextCursor").asText());
        } finally {
            gate.releaseWriter.countDown();
            PUBLICATION_GATE.compareAndSet(gate, null);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentFirstZeroBoundaryRegistrationRetriesWithOneStableToken() throws Exception {
        Account owner = account();
        RegistrationGate gate = new RegistrationGate();
        REGISTRATION_GATE.set(gate);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> get(owner, null, 100));
            assertTrue(gate.inserted.await(10, TimeUnit.SECONDS), "first null pull inserts the zero-boundary token inside its open read transaction");
            var second = pool.submit(() -> get(owner, null, 100));
            awaitConcurrentRegistryInsert();
            assertEquals(0, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=0", Integer.class, owner.id()),
                    "the first transaction's registration is not visible before commit");
            gate.release.countDown();

            Reply a = first.get(15, TimeUnit.SECONDS), b = second.get(15, TimeUnit.SECONDS);
            assertEquals(200, a.status(), a.text());
            assertEquals(200, b.status(), b.text());
            String aCursor = a.json().path("nextCursor").asText();
            assertFalse(aCursor.isBlank());
            assertEquals(aCursor, b.json().path("nextCursor").asText(), "the losing repeatable-read registration retries and returns the winner's stable token");
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=0", Integer.class, owner.id()));
            assertEquals(0, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, owner.id()));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, owner.id()));
        } finally {
            gate.release.countDown();
            REGISTRATION_GATE.compareAndSet(gate, null);
            pool.shutdownNow();
        }
    }

    private void awaitConcurrentRegistryInsert() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid<>pg_backend_pid() " +
                    "and datname=current_database() and wait_event_type='Lock' and query ilike '%insert into sync_cursor_tokens%'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail("second zero-boundary registration never reached PostgreSQL's unique-index wait");
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private Process startProcess(int childPort) {
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath,
                    "com.dragosfotache.gtbackend.GtBackendApplication", "--server.port=" + childPort);
            Map<String, String> env = builder.environment();
            env.put("DB_URL", POSTGRES.getJdbcUrl());
            env.put("DB_USER", POSTGRES.getUsername());
            env.put("DB_PASSWORD", POSTGRES.getPassword());
            env.put("AUTH_ENCRYPTION_KEY", ENCRYPTION_KEY);
            env.put("GOOGLE_OAUTH_AUDIENCE", AUDIENCE);
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        } catch (Exception e) { throw new AssertionError("Could not start test-owned service process", e); }
    }

    private void awaitProcess(int childPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + childPort + "/v1/sync/pull")).GET().build();
                if (HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 401) return;
            } catch (Exception ignored) { }
            Thread.sleep(100);
        }
        throw new AssertionError("test-owned service process failed to start");
    }

    private void stopProcess(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "test-owned service process did not terminate");
        }
        assertTrue(process.exitValue() == 0 || process.exitValue() == 143, "test-owned process stop status: " + process.exitValue());
    }

    @Test
    void issuedCursorAndStoredHistoricalPullSurviveActualHttpProcessRestart() throws Exception {
        Account owner = account();
        int firstPort = freePort();
        Process first = startProcess(firstPort);
        String zeroCursor;
        String root = id();
        try {
            awaitProcess(firstPort);
            Reply empty = request(firstPort, owner.token(), "GET", "/v1/sync/pull", null, null);
            assertEquals(200, empty.status(), empty.text());
            zeroCursor = empty.json().path("nextCursor").asText();
            Reply created = request(firstPort, owner.token(), "POST", "/v1/exercises", "{\"id\":\"" + root + "\",\"name\":\"persist after restart\",\"instructions\":[\"kept\"]}", UUID.randomUUID().toString());
            assertEquals(201, created.status(), created.text());
            Reply firstPull = request(firstPort, owner.token(), "GET", "/v1/sync/pull?cursor=" + URLEncoder.encode(zeroCursor, StandardCharsets.UTF_8), null, null);
            assertEquals(200, firstPull.status(), firstPull.text());
            assertEquals(1, firstPull.json().path("batches").size());
            assertEquals("persist after restart", firstPull.json().path("batches").get(0).path("changes").get(0).path("record").path("name").asText());
        } finally { stopProcess(first); }

        int secondPort = freePort();
        Process second = startProcess(secondPort);
        try {
            awaitProcess(secondPort);
            Reply historical = request(secondPort, owner.token(), "GET", "/v1/sync/pull?cursor=" + URLEncoder.encode(zeroCursor, StandardCharsets.UTF_8), null, null);
            assertEquals(200, historical.status(), historical.text());
            JsonNode change = historical.json().path("batches").get(0).path("changes").get(0);
            assertEquals("customExercise", change.path("aggregateType").asText());
            assertEquals(root, change.path("aggregateId").asText());
            assertEquals("persist after restart", change.path("record").path("name").asText());
            assertEquals("kept", change.path("record").path("instructions").get(0).asText());
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and token=?", Integer.class, owner.id(), zeroCursor));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, owner.id()));
        } finally { stopProcess(second); }
    }

    private void installCursorFault(String name, String sqlState) {
        jdbc.execute("create function " + name + "() returns trigger language plpgsql as $$ begin raise exception 'private-test-fault-marker' using errcode='" + sqlState + "'; end $$");
        jdbc.execute("create trigger " + name + " before insert on sync_cursor_tokens for each row execute function " + name + "()");
    }

    private void removeCursorFault(String name) {
        jdbc.execute("drop trigger " + name + " on sync_cursor_tokens");
        jdbc.execute("drop function " + name + "()");
    }

    @Test
    void transientAndUnexpectedReadRegistrationFaultsAreSanitizedAndRetryWithoutDurableOutcome() {
        for (String[] fault : List.of(new String[]{"test_pull_transient_fault", "40001", "temporarily_unavailable", "503"},
                new String[]{"test_pull_unexpected_fault", "XX000", "internal_error", "500"})) {
            Account owner = account();
            String before = state(owner.id());
            installCursorFault(fault[0], fault[1]);
            try {
                Reply failed = get(owner, null, 100);
                assertEquals(Integer.parseInt(fault[3]), failed.status(), failed.text());
                assertEquals(fault[2], failed.json().path("errorCode").asText());
                assertFalse(failed.text().contains("private-test-fault-marker"));
                assertFalse(failed.text().toLowerCase(java.util.Locale.ROOT).contains("select "));
                assertEquals(before, state(owner.id()), "a failed read cannot persist domain/sync/replay/reservation/credential state");
                assertEquals(0, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=0", Integer.class, owner.id()),
                        "the failed registration transaction cannot leave a boundary token");
            } finally { removeCursorFault(fault[0]); }
            Reply retry = get(owner, null, 100);
            assertEquals(200, retry.status(), retry.text());
            assertTrue(retry.json().path("batches").isEmpty());
            assertTrue(retry.json().path("nextCursor").asText().length() > 1);
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=0", Integer.class, owner.id()));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, owner.id()));
        }
    }
}
