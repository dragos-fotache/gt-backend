package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncSnapshotLifecycleHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "snapshot-lifecycle-audience";
    private static final String PRIVATE_FAULT = "qa-only-snapshot-failure-marker";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("sync.snapshot.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("sync.snapshot.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("sync.history.cleanup.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;
    @Autowired SyncSnapshotCleanupService cleanup;
    @Autowired SyncSnapshotCleanupScheduler scheduler;
    @Autowired SyncHistoryPruningService historyCleanup;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean SessionAuthorization authorization;
    @MockitoSpyBean SyncSnapshotRepository snapshots;

    private record Account(UUID id, String token) { }
    private record Reply(int status, byte[] bytes) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        JsonNode json() {
            try { return JSON.readTree(bytes); }
            catch (Exception e) { throw new AssertionError("Expected JSON response: " + text(), e); }
        }
    }

    private static final class CaptureGate {
        final CountDownLatch writeStored = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterWrite() {
            if (!used.compareAndSet(false, true)) return;
            writeStored.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("test did not release snapshot capture");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("snapshot capture gate interrupted", e);
            }
        }
    }

    private static final class CaptureViewGate {
        final CountDownLatch viewEstablished = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterViewRead() {
            if (!used.compareAndSet(false, true)) return;
            viewEstablished.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("test did not release established capture view");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("capture view gate interrupted", e);
            }
        }
    }

    private static final class HeaderReadGate {
        final CountDownLatch headerRead = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterRead() {
            if (!used.compareAndSet(false, true)) return;
            headerRead.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("test did not release snapshot page read");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("snapshot page gate interrupted", e);
            }
        }
    }

    private static final class PublicationGate {
        final CountDownLatch firstChangeInserted = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterInsert() {
            if (!used.compareAndSet(false, true)) return;
            firstChangeInserted.countDown();
            try {
                if (!releaseWriter.await(20, TimeUnit.SECONDS)) throw new AssertionError("test did not release gated publication");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("publication gate interrupted", e);
            }
        }
    }

    private static final class PruningGate {
        final CountDownLatch deletedChanges = new CountDownLatch(1);
        final CountDownLatch releasePruner = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void afterDelete() {
            if (!used.compareAndSet(false, true)) return;
            deletedChanges.countDown();
            try {
                if (!releasePruner.await(20, TimeUnit.SECONDS)) throw new AssertionError("test did not release gated pruning");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("history pruning gate interrupted", e);
            }
        }
    }

    @AfterEach
    void restoreSpiesAndRemoveTestSnapshots() {
        reset(clock, authorization, snapshots, jdbc);
        jdbc.update("delete from sync_snapshots");
    }

    private void setServiceTime(Instant value) {
        doReturn(value).when(clock).instant();
    }

    private Account account() {
        UUID account = UUID.randomUUID(), session = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", account);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", session, account);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) " +
                "values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '2 hours')", crypto.digest(token), session);
        return new Account(account, token);
    }

    private String id() { return UUID.randomUUID().toString(); }

    private Reply request(Account account, String method, String path, String body, String key) {
        return requestAt(port, account, method, path, body, key);
    }

    private Reply requestAt(int targetPort, Account account, String method, String path, String body, String key) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path));
            if (account != null) builder.header("Authorization", "Bearer " + account.token());
            if (body != null) builder.header("Content-Type", "application/json");
            if (key != null) builder.header("Idempotency-Key", key);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    private Reply snapshot(Account account, String token, Integer limit) {
        List<String> query = new ArrayList<>();
        if (token != null) query.add("pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        if (limit != null) query.add("limit=" + limit);
        return request(account, "GET", "/v1/sync/snapshot" + (query.isEmpty() ? "" : "?" + String.join("&", query)), null, null);
    }

    private String createCustom(Account account, String name) {
        String root = id();
        Reply result = request(account, "POST", "/v1/exercises", "{\"id\":\"" + root + "\",\"name\":\"" + name + "\"}", id());
        assertEquals(201, result.status(), result.text());
        return root;
    }

    private Reply createActiveWorkout(Account account, String workoutId, String customId) {
        Reply created = request(account, "POST", "/v1/workouts", "{\"id\":\"" + workoutId + "\",\"name\":\"gated active\"," +
                "\"startedAt\":\"2025-02-01T00:00:00Z\"}", id());
        if (created.status() != 201) return created;
        String body = "{\"id\":\"" + workoutId + "\",\"name\":\"gated active\",\"status\":\"active\"," +
                "\"startedAt\":\"2025-02-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + id() +
                "\",\"exerciseId\":\"" + customId + "\",\"position\":0,\"notes\":\"before publication\"," +
                "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + id() + "\",\"position\":0," +
                "\"repetitions\":null,\"weightKg\":0,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        return request(account, "PUT", "/v1/workouts/" + workoutId, body, id());
    }

    private String snapshotId(Account account) {
        return jdbc.queryForObject("select snapshot_id from sync_snapshots where account_id=? order by created_at desc,snapshot_id desc limit 1",
                String.class, account.id());
    }

    private void installRecordFault(String functionName, String sqlState) {
        jdbc.execute("create function " + functionName + "() returns trigger language plpgsql as $$ begin " +
                "raise exception '" + PRIVATE_FAULT + "' using errcode='" + sqlState + "'; end $$");
        jdbc.execute("create trigger " + functionName + " before insert on sync_snapshot_records " +
                "for each row execute function " + functionName + "()");
    }

    private void removeRecordFault(String functionName) {
        jdbc.execute("drop trigger " + functionName + " on sync_snapshot_records");
        jdbc.execute("drop function " + functionName + "()");
    }

    private void installDeleteFault(String functionName) {
        jdbc.execute("create function " + functionName + "() returns trigger language plpgsql as $$ begin " +
                "raise exception '" + PRIVATE_FAULT + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger " + functionName + " before delete on sync_snapshots " +
                "for each row execute function " + functionName + "()");
    }

    private void removeDeleteFault(String functionName) {
        jdbc.execute("drop trigger " + functionName + " on sync_snapshots");
        jdbc.execute("drop function " + functionName + "()");
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new AssertionError("Could not allocate test service port", e); }
    }

    private Process startService(int childPort) {
        return startService(childPort, null);
    }

    private Process startService(int childPort, Instant childClock) {
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>(List.of(javaExecutable, "-cp", classpath,
                    "com.dragosfotache.gtbackend.GtBackendApplication", "--server.port=" + childPort));
            if (childClock != null) command.add("--snapshot.qa.fixed-clock=" + childClock);
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> env = builder.environment();
            env.put("DB_URL", POSTGRES.getJdbcUrl());
            env.put("DB_USER", POSTGRES.getUsername());
            env.put("DB_PASSWORD", POSTGRES.getPassword());
            env.put("AUTH_ENCRYPTION_KEY", ENCRYPTION_KEY);
            env.put("GOOGLE_OAUTH_AUDIENCE", AUDIENCE);
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("SYNC_HISTORY_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("SYNC_HISTORY_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("SYNC_SNAPSHOT_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("SYNC_SNAPSHOT_CLEANUP_FIXED_DELAY_MS", "3600000");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        } catch (Exception e) { throw new AssertionError("Could not start test-owned service", e); }
    }

    private void awaitService(int childPort, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + childPort + "/v1/sync/snapshot")).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 401) return;
            } catch (Exception ignored) { }
            Thread.sleep(100);
        }
        throw new AssertionError("test-owned service failed to start");
    }

    private void stopService(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "test-owned service did not terminate");
        }
        assertTrue(process.exitValue() == 0 || process.exitValue() == 143,
                "test-owned service stop status was " + process.exitValue());
    }

    private void assertError(Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.text());
        assertEquals(code, reply.json().path("errorCode").asText());
        assertFalse(reply.text().contains(PRIVATE_FAULT));
    }

    @Test
    void exactExpiryIsNonSlidingAndTypedExpiredTokenSurvivesPayloadCleanupAndRestart() throws Exception {
        Account owner = account();
        createCustom(owner, "expiry one");
        createCustom(owner, "expiry two");
        Instant creation = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(creation);
        Reply first = snapshot(owner, null, 1);
        assertEquals(200, first.status(), first.text());
        String token = first.json().path("nextPageToken").asText();
        String snapshotId = snapshotId(owner);
        assertFalse(token.isBlank());
        Instant persistedCreatedAt = jdbc.queryForObject("select created_at from sync_snapshots where account_id=? and snapshot_id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), owner.id(), UUID.fromString(snapshotId));
        Instant persistedExpiresAt = jdbc.queryForObject("select expires_at from sync_snapshots where account_id=? and snapshot_id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), owner.id(), UUID.fromString(snapshotId));
        assertEquals(creation, persistedCreatedAt);
        assertEquals(creation.plusSeconds(86_400), persistedExpiresAt);

        setServiceTime(creation.plusSeconds(86_399));
        Reply beforeExpiry = snapshot(owner, token, 1);
        assertEquals(200, beforeExpiry.status(), beforeExpiry.text());
        assertEquals(first.json().path("baselineCursor"), beforeExpiry.json().path("baselineCursor"));
        assertEquals(beforeExpiry.json(), snapshot(owner, token, 1).json(), "near-expiry replay does not slide the stored expiry");

        setServiceTime(creation.plusSeconds(86_400));
        assertError(snapshot(owner, token, 2), 400, "invalid_snapshot_token");
        assertError(snapshot(account(), token, 1), 400, "invalid_snapshot_token");
        assertError(snapshot(owner, token, 1), 410, "snapshot_expired");
        setServiceTime(creation.plusSeconds(86_401));
        assertError(snapshot(owner, token, 1), 410, "snapshot_expired");
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), UUID.fromString(snapshotId)));
        assertEquals(1, cleanup.deleteExpiredSnapshots().snapshotsDeleted());
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), UUID.fromString(snapshotId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), UUID.fromString(snapshotId)));
        assertError(snapshot(owner, token, 1), 410, "snapshot_expired");
        assertError(snapshot(account(), token, 1), 400, "invalid_snapshot_token");

        int childPort = freePort();
        Process process = startService(childPort, creation.plusSeconds(86_400));
        try {
            awaitService(childPort, process);
            Reply afterRestart = requestAt(childPort, owner, "GET", "/v1/sync/snapshot?pageToken=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8) + "&limit=1", null, null);
            assertError(afterRestart, 410, "snapshot_expired");
        } finally {
            stopService(process);
        }
    }

    @Test
    void expiryDuringCaptureRollsBackHeaderAndNewZeroBaselineRegistration() throws Exception {
        Account owner = account();
        Instant creation = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(creation);
        CaptureGate gate = new CaptureGate();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            gate.afterWrite();
            return result;
        }).when(snapshots).create(any(UUID.class), any(UUID.class), anyString(), any(Instant.class), any(Instant.class));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> snapshot(owner, null, 10));
            assertTrue(gate.writeStored.await(15, TimeUnit.SECONDS), "empty capture must reach its durable header inside the transaction");
            assertEquals(0, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=?", Integer.class, owner.id()),
                    "the new zero-boundary cursor registration remains uncommitted while the capture gate is held");
            setServiceTime(creation.plusSeconds(86_400));
            gate.release.countDown();
            Reply expired = pending.get(20, TimeUnit.SECONDS);
            assertError(expired, 410, "snapshot_expired");
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, owner.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=?", Integer.class, owner.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=?", Integer.class, owner.id()),
                "a capture expiring before commit rolls back its new baseline token registration");
    }

    @Test
    void captureDatabaseFaultsRollbackAndRetryWithSanitizedHttpErrors() {
        for (String[] fault : List.of(new String[]{"qa_snapshot_serialization_fault", "40001", "503", "temporarily_unavailable"},
                new String[]{"qa_snapshot_unexpected_fault", "XX000", "500", "internal_error"})) {
            Account owner = account();
            createCustom(owner, "fault recovery");
            int snapshotsBefore = jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, owner.id());
            int recordsBefore = jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=?", Integer.class, owner.id());
            int tokensBefore = jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=?", Integer.class, owner.id());
            installRecordFault(fault[0], fault[1]);
            Reply failed;
            try {
                failed = snapshot(owner, null, 5);
            } finally {
                removeRecordFault(fault[0]);
            }
            assertError(failed, Integer.parseInt(fault[2]), fault[3]);
            assertEquals(snapshotsBefore, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, owner.id()));
            assertEquals(recordsBefore, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=?", Integer.class, owner.id()));
            assertEquals(tokensBefore, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=?", Integer.class, owner.id()));
            Reply retry = snapshot(owner, null, 5);
            assertEquals(200, retry.status(), retry.text());
            assertEquals(1, retry.json().path("records").size());
        }
    }

    @Test
    void middleAndFinalPageFaultsRetryTheSameDurableSnapshot() {
        Account owner = account();
        createCustom(owner, "page zero");
        createCustom(owner, "page one");
        createCustom(owner, "page two");
        Reply first = snapshot(owner, null, 1);
        assertEquals(200, first.status(), first.text());
        String baseline = first.json().path("baselineCursor").asText();
        String middleToken = first.json().path("nextPageToken").asText();
        AtomicBoolean failMiddle = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long offset = invocation.getArgument(2);
            if (offset == 1 && failMiddle.compareAndSet(true, false)) throw new IllegalStateException(PRIVATE_FAULT);
            return invocation.callRealMethod();
        }).when(snapshots).readPage(any(UUID.class), any(UUID.class), anyLong(), anyInt(), anyLong());

        Reply middleFailed = snapshot(owner, middleToken, 1);
        assertError(middleFailed, 500, "internal_error");
        Reply middleRetry = snapshot(owner, middleToken, 1);
        assertEquals(200, middleRetry.status(), middleRetry.text());
        assertEquals(baseline, middleRetry.json().path("baselineCursor").asText());
        assertEquals(snapshot(owner, middleToken, 1).json(), middleRetry.json());
        String finalToken = middleRetry.json().path("nextPageToken").asText();
        assertFalse(finalToken.isBlank());

        AtomicBoolean failFinal = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long offset = invocation.getArgument(2);
            if (offset == 2 && failFinal.compareAndSet(true, false)) throw new IllegalStateException(PRIVATE_FAULT);
            return invocation.callRealMethod();
        }).when(snapshots).readPage(any(UUID.class), any(UUID.class), anyLong(), anyInt(), anyLong());
        Reply finalFailed = snapshot(owner, finalToken, 1);
        assertError(finalFailed, 500, "internal_error");
        Reply finalRetry = snapshot(owner, finalToken, 1);
        assertEquals(200, finalRetry.status(), finalRetry.text());
        assertFalse(finalRetry.json().path("hasMore").asBoolean());
        assertEquals(baseline, finalRetry.json().path("baselineCursor").asText());
    }

    @Test
    void v7PayloadConstraintRejectsMissingEnvelopeFieldsAndOutOfRangeRevision() {
        Account owner = account();
        createCustom(owner, "constraint fixture");
        Reply page = snapshot(owner, null, 10);
        assertEquals(200, page.status(), page.text());
        String snapshot = snapshotId(owner);
        UUID snapshotUuid = UUID.fromString(snapshot);

        assertThrows(DataAccessException.class, () -> jdbc.update("update sync_snapshot_records set payload=payload-'aggregateType' " +
                "where account_id=? and snapshot_id=? and ordinal=0", owner.id(), snapshotUuid),
                "missing required JSON keys must fail the database CHECK rather than yield SQL NULL");
        assertThrows(DataAccessException.class, () -> jdbc.update("update sync_snapshot_records set payload=" +
                "jsonb_set(jsonb_set(payload,'{revision}','9223372036854775808'::jsonb)," +
                "'{record,revision}','9223372036854775808'::jsonb) where account_id=? and snapshot_id=? and ordinal=0",
                owner.id(), snapshotUuid), "revision must fit the positive signed Int64 wire type");
        assertEquals(200, snapshot(owner, null, 10).status(), "failed test corruption attempts leave the original snapshot intact");
    }

    @Test
    void cleanupRollbackAndSpringRunnerLogOnlyFixedSanitizedFailure(CapturedOutput output) {
        Account owner = account();
        createCustom(owner, "cleanup rollback");
        Instant creation = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(creation);
        assertEquals(200, snapshot(owner, null, 10).status());
        String snapshotId = snapshotId(owner);
        setServiceTime(creation.plusSeconds(86_400));
        installDeleteFault("qa_snapshot_cleanup_delete_fault");
        try {
            assertDoesNotThrow(scheduler::runCleanup);
            assertTrue(output.getOut().contains("Snapshot cleanup invocation failed"));
            assertFalse(output.getOut().contains(PRIVATE_FAULT));
            assertFalse(output.getOut().contains(owner.id().toString()));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=? and snapshot_id=?",
                    Integer.class, owner.id(), UUID.fromString(snapshotId)), "failed cleanup rolls back the header and cascaded payload deletion");
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                    Integer.class, owner.id(), UUID.fromString(snapshotId)));
        } finally {
            removeDeleteFault("qa_snapshot_cleanup_delete_fault");
        }
        assertEquals(1, cleanup.deleteExpiredSnapshots().snapshotsDeleted(), "a later retry removes the expired frozen payload");
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), UUID.fromString(snapshotId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), UUID.fromString(snapshotId)), "retry removes the target header as well as its payload");
    }

    @Test
    void childCreatedSnapshotContinuesAndReplaysAfterRealServiceProcessRestart() throws Exception {
        Account owner = account();
        String firstRoot = createCustom(owner, "restart first");
        String secondRoot = createCustom(owner, "restart second");
        String expectedFirstRoot = firstRoot.compareTo(secondRoot) < 0 ? firstRoot : secondRoot;
        String expectedFirstName = expectedFirstRoot.equals(firstRoot) ? "restart first" : "restart second";
        String expectedSecondRoot = firstRoot.compareTo(secondRoot) < 0 ? secondRoot : firstRoot;
        String expectedSecondName = expectedSecondRoot.equals(firstRoot) ? "restart first" : "restart second";

        int capturePort = freePort();
        Process captureProcess = startService(capturePort);
        String baseline;
        String token;
        String persistedSnapshotId;
        Instant expiryBefore;
        try {
            awaitService(capturePort, captureProcess);
            Reply first = requestAt(capturePort, owner, "GET", "/v1/sync/snapshot?limit=1", null, null);
            assertEquals(200, first.status(), first.text());
            assertEquals(1, first.json().path("records").size());
            assertTrue(first.json().path("hasMore").asBoolean());
            JsonNode firstChange = first.json().path("records").get(0);
            assertEquals("customExercise", firstChange.path("aggregateType").asText());
            assertEquals(expectedFirstRoot, firstChange.path("aggregateId").asText());
            assertEquals("upsert", firstChange.path("operation").asText());
            assertEquals(1, firstChange.path("revision").asLong());
            assertEquals(expectedFirstRoot, firstChange.path("record").path("id").asText());
            assertEquals(expectedFirstName, firstChange.path("record").path("name").asText());
            assertEquals(firstChange.path("revision").asLong(), firstChange.path("record").path("revision").asLong());
            baseline = first.json().path("baselineCursor").asText();
            assertFalse(baseline.isBlank());
            token = first.json().path("nextPageToken").asText();
            assertFalse(token.isBlank());
            persistedSnapshotId = snapshotId(owner);
            expiryBefore = jdbc.queryForObject("select expires_at from sync_snapshots where account_id=? and snapshot_id=?",
                    (rs, row) -> rs.getTimestamp(1).toInstant(), owner.id(), UUID.fromString(persistedSnapshotId));
        } finally {
            stopService(captureProcess);
        }

        int restartPort = freePort();
        Process restartedProcess = startService(restartPort);
        try {
            awaitService(restartPort, restartedProcess);
            String continuationPath = "/v1/sync/snapshot?pageToken=" +
                    URLEncoder.encode(token, StandardCharsets.UTF_8) + "&limit=1";
            Reply continued = requestAt(restartPort, owner, "GET", continuationPath, null, null);
            assertEquals(200, continued.status(), continued.text());
            assertEquals(baseline, continued.json().path("baselineCursor").asText());
            assertEquals(1, continued.json().path("records").size());
            assertFalse(continued.json().path("hasMore").asBoolean());
            JsonNode secondChange = continued.json().path("records").get(0);
            assertEquals("customExercise", secondChange.path("aggregateType").asText());
            assertEquals(expectedSecondRoot, secondChange.path("aggregateId").asText());
            assertEquals("upsert", secondChange.path("operation").asText());
            assertEquals(1, secondChange.path("revision").asLong());
            assertEquals(expectedSecondRoot, secondChange.path("record").path("id").asText());
            assertEquals(expectedSecondName, secondChange.path("record").path("name").asText());
            assertEquals(secondChange.path("revision").asLong(), secondChange.path("record").path("revision").asLong());
            assertEquals(continued.json(), requestAt(restartPort, owner, "GET", continuationPath, null, null).json(),
                    "restarting the process preserves deterministic replay of the original durable page");
        } finally {
            stopService(restartedProcess);
        }
        Instant expiryAfter = jdbc.queryForObject("select expires_at from sync_snapshots where account_id=? and snapshot_id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), owner.id(), UUID.fromString(persistedSnapshotId));
        assertEquals(expiryBefore, expiryAfter, "capture and continuation across process restart do not replace or slide the persisted lifetime");
    }

    @Test
    void rootsOlderThanPrunedHistoryRemainInTheFullSnapshot() {
        Account owner = account();
        String root = createCustom(owner, "survives pruned history");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(now);
        jdbc.update("update sync_change_batches set committed_at=? where account_id=?",
                java.sql.Timestamp.from(now.minus(91, ChronoUnit.DAYS)), owner.id());

        SyncHistoryPruningService.CleanupProgress pruning = historyCleanup.pruneExpiredHistory();
        assertTrue(pruning.batchesPruned() > 0);
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, owner.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_changes where account_id=?", Integer.class, owner.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercises where owner_id=? and id=? and deleted_at is null",
                Integer.class, owner.id(), UUID.fromString(root)), "pruning history must not purge the live root");

        Reply snapshot = snapshot(owner, null, 200);
        assertEquals(200, snapshot.status(), snapshot.text());
        assertEquals(1, snapshot.json().path("records").size());
        assertEquals(root, snapshot.json().path("records").get(0).path("aggregateId").asText());
        assertEquals("survives pruned history", snapshot.json().path("records").get(0).path("record").path("name").asText());
    }

    @Test
    void captureOverlapsProductionPruningFromOneEstablishedViewAndRetainsLiveRoot() throws Exception {
        Account owner = account();
        String root = createCustom(owner, "root during pruning");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(now);
        jdbc.update("update sync_change_batches set committed_at=? where account_id=?",
                java.sql.Timestamp.from(now.minus(91, ChronoUnit.DAYS)), owner.id());

        CaptureViewGate captureGate = new CaptureViewGate();
        PruningGate pruningGate = new PruningGate();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            captureGate.afterViewRead();
            return result;
        }).when(authorization).requireLiveAccessForRead(anyString());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("delete from sync_changes"))
                pruningGate.afterDelete();
            return result;
        }).when(jdbc).update(anyString(), any(Object[].class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var pruning = executor.submit(historyCleanup::pruneExpiredHistory);
            assertTrue(pruningGate.deletedChanges.await(15, TimeUnit.SECONDS),
                    "production pruning has deleted the expired history prefix inside its still-open account transaction");
            var pendingCapture = executor.submit(() -> snapshot(owner, null, 10));
            assertTrue(captureGate.viewEstablished.await(15, TimeUnit.SECONDS),
                    "snapshot establishes one repeatable-read view while production pruning remains uncommitted");
            pruningGate.releasePruner.countDown();
            SyncHistoryPruningService.CleanupProgress pruned = pruning.get(10, TimeUnit.SECONDS);
            assertTrue(pruned.batchesPruned() > 0);
            captureGate.release.countDown();

            Reply captured = pendingCapture.get(20, TimeUnit.SECONDS);
            assertEquals(200, captured.status(), captured.text());
            assertEquals(1, captured.json().path("records").size());
            assertEquals(root, captured.json().path("records").get(0).path("aggregateId").asText());
            assertEquals("root during pruning", captured.json().path("records").get(0).path("record").path("name").asText());
            Reply pull = request(owner, "GET", "/v1/sync/pull?cursor=" +
                    URLEncoder.encode(captured.json().path("baselineCursor").asText(), StandardCharsets.UTF_8), null, null);
            assertEquals(200, pull.status(), pull.text());
            assertTrue(pull.json().path("batches").isEmpty(), "pruning of history at or before the baseline does not invalidate that baseline");
        } finally {
            pruningGate.releasePruner.countDown();
            captureGate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentWriterCannotCreateHybridSnapshotAndLaterChangePullsFromBaseline() throws Exception {
        Account owner = account();
        String root = createCustom(owner, "before gated update");
        CaptureViewGate gate = new CaptureViewGate();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            gate.afterViewRead();
            return result;
        }).when(authorization).requireLiveAccessForRead(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var pending = executor.submit(() -> snapshot(owner, null, 10));
            assertTrue(gate.viewEstablished.await(15, TimeUnit.SECONDS),
                    "snapshot establishes its repeatable-read view after live authorization and before any snapshot/cursor writes");
            var writer = executor.submit(() -> request(owner, "PUT", "/v1/exercises/" + root,
                    "{\"id\":\"" + root + "\",\"name\":\"after concurrent commit\"}", id()));
            Reply update = writer.get(10, TimeUnit.SECONDS);
            assertEquals(200, update.status(), update.text());
            gate.release.countDown();
            Reply captured = pending.get(20, TimeUnit.SECONDS);
            assertEquals(200, captured.status(), captured.text());
            assertEquals(1, captured.json().path("records").size());
            assertEquals("before gated update", captured.json().path("records").get(0).path("record").path("name").asText(),
                    "snapshot capture must retain the one pre-writer consistent view");

            String baseline = captured.json().path("baselineCursor").asText();
            Reply pull = request(owner, "GET", "/v1/sync/pull?cursor=" +
                    URLEncoder.encode(baseline, StandardCharsets.UTF_8), null, null);
            assertEquals(200, pull.status(), pull.text());
            assertEquals(1, pull.json().path("batches").size());
            JsonNode change = pull.json().path("batches").get(0).path("changes").get(0);
            assertEquals(root, change.path("aggregateId").asText());
            assertEquals("after concurrent commit", change.path("record").path("name").asText());
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void captureDuringAllocatedUncommittedMultiRootPublicationKeepsOneViewAndPullsTheWholeCommit() throws Exception {
        Account owner = account();
        String customId = createCustom(owner, "publication dependency");
        String oldWorkoutId = id();
        Reply oldWorkout = createActiveWorkout(owner, oldWorkoutId, customId);
        assertEquals(200, oldWorkout.status(), oldWorkout.text());

        CaptureViewGate captureGate = new CaptureViewGate();
        PublicationGate publicationGate = new PublicationGate();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            captureGate.afterViewRead();
            return result;
        }).when(authorization).requireLiveAccessForRead(anyString());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("insert into sync_changes"))
                publicationGate.afterInsert();
            return result;
        }).when(jdbc).update(anyString(), any(Object[].class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String newWorkoutId = id();
            var writer = executor.submit(() -> request(owner, "POST", "/v1/workouts",
                    "{\"id\":\"" + newWorkoutId + "\",\"name\":\"new active\"," +
                            "\"startedAt\":\"2025-02-02T00:00:00Z\"}", id()));
            assertTrue(publicationGate.firstChangeInserted.await(15, TimeUnit.SECONDS),
                    "active-workout replacement has allocated its cursor and inserted the first change before commit");
            var pendingCapture = executor.submit(() -> snapshot(owner, null, 10));
            assertTrue(captureGate.viewEstablished.await(15, TimeUnit.SECONDS),
                    "capture establishes its repeatable-read view while the publication remains uncommitted");
            publicationGate.releaseWriter.countDown();
            Reply accepted = writer.get(10, TimeUnit.SECONDS);
            assertEquals(201, accepted.status(), accepted.text());
            captureGate.release.countDown();

            Reply captured = pendingCapture.get(20, TimeUnit.SECONDS);
            assertEquals(200, captured.status(), captured.text());
            assertEquals(2, captured.json().path("records").size());
            assertTrue(captured.json().path("records").findValuesAsText("aggregateId").contains(oldWorkoutId));
            assertFalse(captured.json().path("records").findValuesAsText("aggregateId").contains(newWorkoutId));
            assertEquals("gated active", captured.json().path("records").get(1).path("record").path("name").asText());

            Reply laterPull = request(owner, "GET", "/v1/sync/pull?cursor=" +
                    URLEncoder.encode(captured.json().path("baselineCursor").asText(), StandardCharsets.UTF_8), null, null);
            assertEquals(200, laterPull.status(), laterPull.text());
            assertEquals(1, laterPull.json().path("batches").size());
            JsonNode batch = laterPull.json().path("batches").get(0);
            assertEquals(2, batch.path("changes").size(), "the allocated multi-root publication is complete after commit");
            assertEquals(oldWorkoutId, batch.path("changes").get(0).path("aggregateId").asText());
            assertEquals("delete", batch.path("changes").get(0).path("operation").asText());
            assertEquals(newWorkoutId, batch.path("changes").get(1).path("aggregateId").asText());
            assertEquals("upsert", batch.path("changes").get(1).path("operation").asText());
        } finally {
            publicationGate.releaseWriter.countDown();
            captureGate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void continuationRacingExpiredPayloadCleanupIsCoherentAndNeverReturnsHybridFailure() throws Exception {
        Account owner = account();
        createCustom(owner, "cleanup race first");
        createCustom(owner, "cleanup race second");
        Instant creation = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(creation);
        Reply first = snapshot(owner, null, 1);
        assertEquals(200, first.status(), first.text());
        String token = first.json().path("nextPageToken").asText();
        setServiceTime(creation.plusSeconds(86_399));
        HeaderReadGate gate = new HeaderReadGate();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            gate.afterRead();
            return result;
        }).when(snapshots).findHeader(any(UUID.class), any(UUID.class));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> snapshot(owner, token, 1));
            assertTrue(gate.headerRead.await(15, TimeUnit.SECONDS), "continuation has read one complete durable header in its RR view");
            setServiceTime(creation.plusSeconds(86_400));
            assertEquals(1, cleanup.deleteExpiredSnapshots().snapshotsDeleted());
            gate.release.countDown();
            Reply result = pending.get(20, TimeUnit.SECONDS);
            assertError(result, 410, "snapshot_expired");
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundedCleanupMakesProgressAcrossFiniteTicksAndPreservesNewSnapshot() {
        Account owner = account();
        Account protectedAccount = account();
        Instant firstCreation = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setServiceTime(firstCreation);
        for (int i = 0; i < 205; i++) {
            Reply created = snapshot(owner, null, 200);
            assertEquals(200, created.status(), created.text());
        }
        setServiceTime(firstCreation.plusSeconds(86_400));
        Reply live = snapshot(protectedAccount, null, 200);
        assertEquals(200, live.status(), live.text());

        scheduler.runCleanup();
        assertEquals(105, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, owner.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, protectedAccount.id()));
        SyncSnapshotCleanupService.CleanupProgress next = cleanup.deleteExpiredSnapshots();
        assertEquals(100, next.snapshotsDeleted());
        assertTrue(next.mayHaveMore());
        SyncSnapshotCleanupService.CleanupProgress last = cleanup.deleteExpiredSnapshots();
        assertEquals(5, last.snapshotsDeleted());
        assertFalse(last.mayHaveMore());
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, owner.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_snapshots where account_id=?", Integer.class, protectedAccount.id()));
    }
}
