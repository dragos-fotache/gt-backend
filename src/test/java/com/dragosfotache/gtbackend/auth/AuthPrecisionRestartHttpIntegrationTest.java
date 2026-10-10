package com.dragosfotache.gtbackend.auth;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.ACCESS_LIFETIME_SECONDS;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.REFRESH_LIFETIME_DAYS;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.assertCredentialBytesEqual;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.assertLosslessMillis;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.parseExpiry;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actual service process stop/restart for auth grant precision and replay durability.
 *
 * <p>The parent orchestrates the isolated database and child routing but never accepts
 * auth itself. Children run on a deterministic test-only fixed clock carrying nonzero
 * microsecond/nanosecond fractions (active only via the owned {@code auth.qa.child-clock}
 * property), so lossless-expiry assertions exercise the production truncation boundary
 * across processes. Child A performs real HTTP acceptance against the isolated database;
 * the parent stops A and asserts death before recovery; child B replays the identical
 * original keys and bodies with unchanged stored deadlines.
 *
 * <p>Replay evidence keeps independent journeys apart: the untouched exchange session
 * replays successfully before and after restart, the rotated session's refresh replays
 * successfully before and after restart, and the superseded original exchange is
 * rejected with {@code 401 reauthentication_required}. No retry keys are invented for
 * replay. Child logs are preserved under {@code /tmp/gt-backend-auth-qa-child-logs/}
 * and never deleted on failure.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthPrecisionRestartHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CHILD_BASE = "2026-10-04T12:00:00.123456789Z";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "auth-precision-restart-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("auth.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("auth.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("sync.history.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("sync.history.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("sync.snapshot.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("sync.snapshot.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("account.deletion.purge.initial-delay-ms", () -> "3600000");
        registry.add("account.deletion.purge.fixed-delay-ms", () -> "3600000");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private record Reply(int status, String body) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON");
            }
        }
    }

    private Reply postTo(int targetPort, String path, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + targetPort + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path);
        }
    }

    private Reply challengeVia(int targetPort) {
        Reply reply = postTo(targetPort, "/v1/auth/google/challenge", "{}", null);
        assertEquals(200, reply.status, "child challenge issuance failed");
        assertLosslessMillis(parseExpiry("child challenge", reply.node(), "expiresAt"), "child challenge expiresAt");
        return reply;
    }

    private record Accepted(String body, Reply grant) {
    }

    private Accepted exchangeVia(int targetPort, String token, String key, Path log) {
        Reply challenge = challengeVia(targetPort);
        String body = "{\"challengeId\":\"" + challenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"" + token + "\"}";
        Reply grant = postTo(targetPort, "/v1/auth/google", body, Map.of("Idempotency-Key", key));
        assertEquals(200, grant.status, "child exchange failed, log: " + log);
        return new Accepted(body, grant);
    }

    @Test
    void restartPreservesExactReplayAcrossActualProcessStop() throws Exception {
        Instant childIssued = truncated(Instant.parse(CHILD_BASE));
        Path logDir = Path.of("/tmp/gt-backend-auth-qa-child-logs");
        Files.createDirectories(logDir);
        int portA = freePort();
        Path logA = logDir.resolve("auth-precision-a-" + System.nanoTime() + ".log");
        Process childA = startServiceProcess(portA, logA, CHILD_BASE);
        String exchangeKey1 = UUID.randomUUID().toString();
        String exchangeBody1;
        String exchangeResponse1;
        String exchangeKey2 = UUID.randomUUID().toString();
        String exchangeBody2;
        String refreshKey = UUID.randomUUID().toString();
        String refreshBody;
        String refreshResponse;
        String accountId;
        try {
            awaitProcessReady(childA, portA);
            Accepted first = exchangeVia(portA, "child-exchange-s1", exchangeKey1, logA);
            exchangeBody1 = first.body();
            exchangeResponse1 = first.grant().body;
            assertEquals(childIssued.plusSeconds(ACCESS_LIFETIME_SECONDS),
                    parseExpiry("child exchange", first.grant().node(), "accessExpiresAt"),
                    "child access lifetime must be exactly 15 minutes from truncated child issuance");
            assertEquals(childIssued.plus(java.time.Duration.ofDays(REFRESH_LIFETIME_DAYS)),
                    parseExpiry("child exchange", first.grant().node(), "refreshExpiresAt"),
                    "child refresh lifetime must be exactly 30 days from truncated child issuance");
            accountId = first.grant().node().path("account").path("id").asText();

            Accepted second = exchangeVia(portA, "child-exchange-s2", exchangeKey2, logA);
            exchangeBody2 = second.body();
            refreshBody = "{\"refreshToken\":\"" + second.grant().node().path("refreshToken").asText() + "\"}";
            Reply rotated = postTo(portA, "/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey));
            assertEquals(200, rotated.status, "child A log: " + logA);
            assertLosslessMillis(parseExpiry("child refresh", rotated.node(), "accessExpiresAt"),
                    "child rotated accessExpiresAt");
            assertLosslessMillis(parseExpiry("child refresh", rotated.node(), "refreshExpiresAt"),
                    "child rotated refreshExpiresAt");
            refreshResponse = rotated.body;

            Reply replay1 = postTo(portA, "/v1/auth/google", exchangeBody1, Map.of("Idempotency-Key", exchangeKey1));
            assertEquals(200, replay1.status, "child A log: " + logA);
            assertCredentialBytesEqual(exchangeResponse1.getBytes(StandardCharsets.UTF_8),
                    replay1.body.getBytes(StandardCharsets.UTF_8), "pre-restart untouched exchange replay");
            Reply replayR = postTo(portA, "/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey));
            assertEquals(200, replayR.status, "child A log: " + logA);
            assertCredentialBytesEqual(refreshResponse.getBytes(StandardCharsets.UTF_8),
                    replayR.body.getBytes(StandardCharsets.UTF_8), "pre-restart refresh replay");

            childA.destroyForcibly();
            assertTrue(childA.waitFor(15, TimeUnit.SECONDS), "child A did not terminate");
            assertTrue(!childA.isAlive(), "child A must be dead before recovery");
        } finally {
            if (childA.isAlive()) {
                childA.destroyForcibly();
                childA.waitFor(15, TimeUnit.SECONDS);
            }
        }

        int credentialsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_credentials", Integer.class);
        int portB = freePort();
        Path logB = logDir.resolve("auth-precision-b-" + System.nanoTime() + ".log");
        Process childB = startServiceProcess(portB, logB, CHILD_BASE);
        try {
            awaitProcessReady(childB, portB);
            assertNotNull(exchangeBody1);
            Reply recovered1 = postTo(portB, "/v1/auth/google", exchangeBody1,
                    Map.of("Idempotency-Key", exchangeKey1));
            assertEquals(200, recovered1.status, "child B log: " + logB);
            assertCredentialBytesEqual(exchangeResponse1.getBytes(StandardCharsets.UTF_8),
                    recovered1.body.getBytes(StandardCharsets.UTF_8), "post-restart untouched exchange replay");
            assertEquals(parseExpiry("original exchange", JSON.readTree(exchangeResponse1), "refreshExpiresAt"),
                    parseExpiry("post-restart replay", recovered1.node(), "refreshExpiresAt"),
                    "post-restart replay must return the original expiry, not an extension");
            assertEquals(accountId, recovered1.node().path("account").path("id").asText(),
                    "post-restart replay must preserve account identity");

            Reply recoveredR = postTo(portB, "/v1/auth/refresh", refreshBody,
                    Map.of("Idempotency-Key", refreshKey));
            assertEquals(200, recoveredR.status, "child B log: " + logB);
            assertCredentialBytesEqual(refreshResponse.getBytes(StandardCharsets.UTF_8),
                    recoveredR.body.getBytes(StandardCharsets.UTF_8), "post-restart refresh replay");

            Reply superseded = postTo(portB, "/v1/auth/google", exchangeBody2,
                    Map.of("Idempotency-Key", exchangeKey2));
            assertEquals(401, superseded.status, "superseded original exchange must be rejected after rotation");
            assertEquals("reauthentication_required", superseded.node().path("errorCode").asText(),
                    "superseded exchange must report reauthentication_required");

            assertEquals(credentialsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_credentials", Integer.class),
                    "post-restart replays must not mint credentials");
        } finally {
            if (childB.isAlive()) {
                childB.destroy();
                if (!childB.waitFor(15, TimeUnit.SECONDS)) {
                    childB.destroyForcibly();
                    childB.waitFor(15, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void restartPreservesReplayWithExpiredAccessAndFreshRotationStaysCompatible() throws Exception {
        Path logDir = Path.of("/tmp/gt-backend-auth-qa-child-logs");
        Files.createDirectories(logDir);
        int portA = freePort();
        Path logA = logDir.resolve("auth-expired-a-" + System.nanoTime() + ".log");
        Process childA = startServiceProcess(portA, logA, CHILD_BASE);
        String exchangeBody;
        String exchangeKey = UUID.randomUUID().toString();
        String exchangeResponse;
        String refreshToken;
        Instant accessExpiresAt;
        try {
            awaitProcessReady(childA, portA);
            Reply challenge = challengeVia(portA);
            exchangeBody = "{\"challengeId\":\"" + challenge.node().path("challengeId").asText()
                    + "\",\"idToken\":\"child-expired-access\"}";
            Reply grant = postTo(portA, "/v1/auth/google", exchangeBody, Map.of("Idempotency-Key", exchangeKey));
            assertEquals(200, grant.status, "child A log: " + logA);
            exchangeResponse = grant.body;
            refreshToken = grant.node().path("refreshToken").asText();
            accessExpiresAt = parseExpiry("child exchange", grant.node(), "accessExpiresAt");
            childA.destroyForcibly();
            assertTrue(childA.waitFor(15, TimeUnit.SECONDS), "child A did not terminate");
            assertTrue(!childA.isAlive(), "child A must be dead before recovery");
        } finally {
            if (childA.isAlive()) {
                childA.destroyForcibly();
                childA.waitFor(15, TimeUnit.SECONDS);
            }
        }

        int portB = freePort();
        Path logB = logDir.resolve("auth-expired-b-" + System.nanoTime() + ".log");
        Process childB = startServiceProcess(portB, logB, accessExpiresAt.toString());
        try {
            awaitProcessReady(childB, portB);
            assertNotNull(exchangeBody);
            Reply recovered = postTo(portB, "/v1/auth/google", exchangeBody,
                    Map.of("Idempotency-Key", exchangeKey));
            assertEquals(200, recovered.status, "child B log: " + logB);
            assertCredentialBytesEqual(exchangeResponse.getBytes(StandardCharsets.UTF_8),
                    recovered.body.getBytes(StandardCharsets.UTF_8), "expired-access post-restart replay");
            assertEquals(parseExpiry("original exchange", JSON.readTree(exchangeResponse), "refreshExpiresAt"),
                    parseExpiry("expired-access replay", recovered.node(), "refreshExpiresAt"),
                    "expired-access replay must return the original expiry with unchanged stored deadlines");

            Reply rotated = postTo(portB, "/v1/auth/refresh",
                    "{\"refreshToken\":\"" + refreshToken + "\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, rotated.status, "child B log: " + logB);
            assertLosslessMillis(parseExpiry("post-restart rotation", rotated.node(), "accessExpiresAt"),
                    "post-restart rotated accessExpiresAt");
            assertLosslessMillis(parseExpiry("post-restart rotation", rotated.node(), "refreshExpiresAt"),
                    "post-restart rotated refreshExpiresAt");
        } finally {
            if (childB.isAlive()) {
                childB.destroy();
                if (!childB.waitFor(15, TimeUnit.SECONDS)) {
                    childB.destroyForcibly();
                    childB.waitFor(15, TimeUnit.SECONDS);
                }
            }
        }
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Process startServiceProcess(int childPort, Path log, String childClock) {
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath,
                    "com.dragosfotache.gtbackend.GtBackendApplication",
                    "--server.port=" + childPort, "--server.address=127.0.0.1");
            Map<String, String> env = builder.environment();
            env.put("DB_URL", POSTGRES.getJdbcUrl());
            env.put("DB_USER", POSTGRES.getUsername());
            env.put("DB_PASSWORD", POSTGRES.getPassword());
            env.put("AUTH_ENCRYPTION_KEY", ENCRYPTION_KEY);
            env.put("GOOGLE_OAUTH_AUDIENCE", "auth-precision-restart-audience");
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("SYNC_HISTORY_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("SYNC_HISTORY_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("SYNC_SNAPSHOT_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("SYNC_SNAPSHOT_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("ACCOUNT_DELETION_PURGE_INITIAL_DELAY_MS", "3600000");
            env.put("ACCOUNT_DELETION_PURGE_FIXED_DELAY_MS", "3600000");
            String toolOptions = "-Dauth.qa.child-google.enabled=true";
            if (childClock != null) {
                toolOptions += " -Dauth.qa.child-clock=" + childClock;
            }
            env.put("JAVA_TOOL_OPTIONS", toolOptions);
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            return builder.start();
        } catch (Exception e) {
            throw new AssertionError("Could not start test-owned service process");
        }
    }

    private void awaitProcessReady(Process process, int childPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Test-owned service exited during startup");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + childPort + "/v1/sync/pull")).GET().build();
                int status = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 401) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Test-owned service process failed to start");
    }
}
