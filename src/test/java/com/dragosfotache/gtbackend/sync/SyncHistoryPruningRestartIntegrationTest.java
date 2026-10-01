package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies scheduled cleanup, safe logged failure/retry, and durable pull/replay after process restart. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncHistoryPruningRestartIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "sync-pruning-restart-audience";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("sync.history.cleanup.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;

    private record Account(UUID id, String bearer) { }
    private record Reply(int status, String body) {
        JsonNode json() {
            try { return JSON.readTree(body); }
            catch (Exception e) { throw new AssertionError(body, e); }
        }
    }

    private Account account() {
        UUID account = UUID.randomUUID(), session = UUID.randomUUID();
        String bearer = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", account);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", session, account);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) "
                + "values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '2 hours')", crypto.digest(bearer), session);
        return new Account(account, bearer);
    }

    private Reply request(int targetPort, Account account, String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                    .header("Authorization", "Bearer " + account.bearer());
            if (body != null) builder.header("Content-Type", "application/json");
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    @Test
    void scheduledRunnerRetriesSanitizedFailureAndRestartPreservesPrunedBoundaryAndReplay() throws Exception {
        Account account = account();
        String zeroCursor = request(port, account, "GET", "/v1/sync/pull", null).json().path("nextCursor").asText();
        UUID mutationId = UUID.randomUUID();
        String root = UUID.randomUUID().toString();
        String mutation = "{\"mutationId\":\"" + mutationId + "\",\"type\":\"customExercise.upsert\","
                + "\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,"
                + "\"payload\":{\"id\":\"" + root + "\",\"name\":\"restart replay evidence\"}}";
        Reply accepted = request(port, account, "POST", "/v1/sync/push", "{\"mutations\":[" + mutation + "]}");
        assertEquals(200, accepted.status(), accepted.body());
        JsonNode originalBatch = accepted.json().path("results").get(0).path("batch");
        assertEquals(1, jdbc.update("update sync_change_batches set committed_at=? where account_id=? and cursor=1",
                Timestamp.from(Instant.now().minusSeconds(91L * 86_400)), account.id()));

        Path log = Files.createTempFile("task8b-cleanup-process-", ".log");
        String trigger = "task8b_restart_failure";
        createFailureTrigger(trigger, account.id());
        int firstPort = freePort();
        Process first = startServiceProcess(firstPort, log);
        try {
            awaitProcessReady(first, firstPort);
            awaitLog(log, "Sync-history cleanup invocation failed; uncommitted account work was rolled back.");
            assertEquals(0, watermark(account), "scheduled failure cannot partially advance the durable boundary");
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id()));
            String firstLog = Files.readString(log);
            assertFalse(firstLog.contains("task8b-private-database-marker"));
            assertFalse(firstLog.toLowerCase(java.util.Locale.ROOT).contains("update account_sync_state"));
            dropFailureTrigger(trigger);
            awaitWatermark(account, 1); // the next scheduled invocation recovers and commits
        } finally {
            dropFailureTrigger(trigger);
            stopProcess(first);
        }

        int restartPort = freePort();
        Process restarted = startServiceProcess(restartPort, log);
        try {
            awaitProcessReady(restarted, restartPort);
            Reply expired = request(restartPort, account, "GET", "/v1/sync/pull?cursor="
                    + URLEncoder.encode(zeroCursor, StandardCharsets.UTF_8), null);
            assertEquals(410, expired.status(), expired.body());
            awaitWatermark(account, 1);

            Reply replay = request(restartPort, account, "POST", "/v1/sync/push", "{\"mutations\":[" + mutation + "]}");
            assertEquals(200, replay.status(), replay.body());
            JsonNode outcome = replay.json().path("results").get(0);
            assertEquals("duplicate", outcome.path("status").asText());
            assertEquals(originalBatch, outcome.path("batch"), "all original batch fields and ordered changes survive pruning and restart");
            Reply retainedStart = request(restartPort, account, "GET", "/v1/sync/pull", null);
            assertEquals(200, retainedStart.status(), retainedStart.body());
            assertTrue(retainedStart.json().path("batches").isEmpty());
            assertTrue(retainedStart.json().path("nextCursor").asText().length() > 1);
            assertEquals(1, watermark(account));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and token=?",
                    Integer.class, account.id(), zeroCursor));
        } finally {
            stopProcess(restarted);
            Files.deleteIfExists(log);
        }
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private Process startServiceProcess(int childPort, Path log) {
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
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
            env.put("SYNC_HISTORY_CLEANUP_ENABLED", "true");
            env.put("SYNC_HISTORY_CLEANUP_INITIAL_DELAY_MS", "100");
            env.put("SYNC_HISTORY_CLEANUP_FIXED_DELAY_MS", "200");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            return builder.start();
        } catch (Exception e) { throw new AssertionError("Could not start test-owned service process", e); }
    }

    private void awaitProcessReady(Process process, int childPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Test-owned service exited during startup");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + childPort + "/v1/sync/pull")).GET().build();
                if (HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 401) return;
            } catch (Exception ignored) { }
            Thread.sleep(100);
        }
        throw new AssertionError("Test-owned service process failed to start");
    }

    private void awaitLog(Path log, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (Files.readString(log).contains(message)) return;
            Thread.onSpinWait();
        }
        fail("Scheduled runner did not report its bounded sanitized failure");
    }

    private void awaitWatermark(Account account, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select pruned_through from account_sync_state where account_id=?", Long.class, account.id()) == expected) return;
            Thread.onSpinWait();
        }
        fail("Scheduled cleanup did not commit the expected durable watermark");
    }

    private void stopProcess(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "test-owned service process did not terminate");
        }
        assertTrue(process.exitValue() == 0 || process.exitValue() == 143, "service process exit code " + process.exitValue());
    }

    private long watermark(Account account) {
        return jdbc.queryForObject("select pruned_through from account_sync_state where account_id=?", Long.class, account.id());
    }

    private void createFailureTrigger(String name, UUID accountId) {
        jdbc.execute("create function " + name + "() returns trigger language plpgsql as $$ begin "
                + "if NEW.account_id='" + accountId + "'::uuid then "
                + "raise exception 'task8b-private-database-marker' using errcode='XX000'; end if; return NEW; end $$");
        jdbc.execute("create trigger " + name + " before update of pruned_through on account_sync_state "
                + "for each row execute function " + name + "()");
    }

    private void dropFailureTrigger(String name) {
        jdbc.execute("drop trigger if exists " + name + " on account_sync_state");
        jdbc.execute("drop function if exists " + name + "()");
    }
}
