package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Durability, equality and historical-version coverage for deletion.
 *
 * <p>Actual originator death-before-recovery uses test-owned child processes: child A
 * performs the real deletion HTTP acceptance over real HTTP against the isolated
 * database, the parent stops A and asserts death before recovery, then child B
 * recovers the identical receipt. The parent orchestrates Testcontainers and HTTP
 * routing but never accepts the deletion itself. The exact accepted request under its
 * original key replays as 202 through B. Controlled ledger-only fixtures are
 * supplementary and are not actual purge evidence. Child logs are preserved under
 * {@code /tmp/gt-backend-task9b-qa-child-logs/} and are never deleted on failure.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionRestartHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SYNTHETIC_RING =
            "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}";
    private static final String SYNTHETIC_RING_V1_V2 =
            "{\"activeVersion\":\"2\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\","
                    + "\"2\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\"}}";
    private static final String SYNTHETIC_RING_V2_ONLY =
            "{\"activeVersion\":\"2\",\"keys\":{\"2\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\"}}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "deletion-restart-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock restartClock;
    @Autowired
    TestAccountDeletionHooks deletionHooks;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void set(Instant instant) {
            now.set(instant);
        }
    }

    @TestConfiguration
    static class RestartClockConfiguration {
        @Bean
        @Primary
        MutableClock restartClock() {
            return new MutableClock();
        }
    }

    private record ProofChallenge(String id, String nonce) {}
    private record Reply(int status, String body) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body, e);
            }
        }
    }

    private Reply postTo(int targetPort, String path, String body, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + targetPort + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private Reply post(String path, String body, Map<String, String> extraHeaders) {
        return postTo(port, path, body, extraHeaders);
    }

    private ProofChallenge challengeVia(int targetPort, String purposeBody) {
        Reply reply = postTo(targetPort, "/v1/auth/google/challenge", purposeBody, null);
        assertEquals(200, reply.status, reply.body);
        return new ProofChallenge(reply.node().path("challengeId").asText(), reply.node().path("nonce").asText());
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    @Test
    void proofExpiringDuringScopedKeyWaitIsRejectedWithoutConsumptionOrLedger() throws Exception {
        ProofChallenge proof = challengeVia(port, "{\"purpose\":\"deleteAccount\"}");
        // Provision through the parent Spring context only to own an account; the
        // expiring proof itself targets that account and is never accepted.
        ProofChallenge signup = challengeVia(port, "{}");
        lenient().when(verifier.verify("restart-lock-signup", signup.nonce))
                .thenReturn(new Verified("restart-lock-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"restart-lock-signup\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        UUID accountId = UUID.fromString(grant.node().path("account").path("id").asText());
        lenient().when(verifier.verify("restart-lock-token", proof.nonce))
                .thenReturn(new Verified("restart-lock-subject", null));
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?",
                java.sql.Timestamp.class, UUID.fromString(proof.id)).toInstant();

        deletionHooks.armScopedKeyGate();
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + proof.id
                + "\",\"idToken\":\"restart-lock-token\",\"confirm\":true}";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> pending = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitScopedKeyEntered(), "deletion never reached scoped-key gate");
            restartClock.set(expires.plusMillis(1));
            deletionHooks.releaseScopedKey();
            Reply reply = pending.get(60, TimeUnit.SECONDS);
            assertEquals(401, reply.status, reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertFalse(challengeConsumed(proof.id));
            assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                    Boolean.class, accountId));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                    accountId));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?", Integer.class,
                    accountId));
        } finally {
            deletionHooks.releaseScopedKey();
            pool.shutdownNow();
            restartClock.set(Instant.now());
        }
    }

    @Test
    void strictEqualityChallengeIsDeniedAndRepeatedDeletionPreservesOriginalReceipt() {
        ProofChallenge signup = challengeVia(port, "{}");
        lenient().when(verifier.verify("repeated-a", signup.nonce))
                .thenReturn(new Verified("repeated-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"repeated-a\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        UUID oldAccount = UUID.fromString(grant.node().path("account").path("id").asText());

        ProofChallenge firstProof = challengeVia(port, "{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("repeated-first", firstProof.nonce))
                .thenReturn(new Verified("repeated-subject", null));
        String firstKey = UUID.randomUUID().toString();
        Reply first = post("/v1/account/delete",
                "{\"accountId\":\"" + oldAccount + "\",\"challengeId\":\"" + firstProof.id
                        + "\",\"idToken\":\"repeated-first\",\"confirm\":true}",
                Map.of("Idempotency-Key", firstKey));
        assertEquals(202, first.status, first.body);
        Instant deletedAt = Instant.parse(first.node().path("deletedAt").asText());
        Instant purgeBy = Instant.parse(first.node().path("purgeBy").asText());

        ProofChallenge equality = challengeVia(port, "{}");
        jdbc.update("UPDATE auth_challenges SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(deletedAt), UUID.fromString(equality.id));
        lenient().when(verifier.verify("repeated-equality", equality.nonce))
                .thenReturn(new Verified("repeated-subject", null));
        Reply equalityDenied = post("/v1/auth/google",
                "{\"challengeId\":\"" + equality.id + "\",\"idToken\":\"repeated-equality\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, equalityDenied.status, equalityDenied.body);
        assertEquals("invalid_google_credential", equalityDenied.node().path("errorCode").asText());

        ProofChallenge secondProof = challengeVia(port, "{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("repeated-second", secondProof.nonce))
                .thenReturn(new Verified("repeated-subject", null));
        Reply second = post("/v1/account/delete",
                "{\"accountId\":\"" + oldAccount + "\",\"challengeId\":\"" + secondProof.id
                        + "\",\"idToken\":\"repeated-second\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(409, second.status, second.body);
        assertEquals("invalid_state", second.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                oldAccount));
        java.sql.Timestamp storedDeleted = jdbc.queryForObject(
                "SELECT deleted_at FROM account_deletion_ledger WHERE old_account_id=?",
                java.sql.Timestamp.class, oldAccount);
        java.sql.Timestamp storedPurge = jdbc.queryForObject(
                "SELECT purge_by FROM account_deletion_ledger WHERE old_account_id=?",
                java.sql.Timestamp.class, oldAccount);
        assertEquals(deletedAt, storedDeleted.toInstant());
        assertEquals(purgeBy, storedPurge.toInstant());
    }

    @Test
    void actualOriginatorDeathBeforeRecoveryPreservesReceipt() throws Exception {
        // Parent orchestrates the isolated database and child routing but never accepts.
        int childAPort = freePort();
        Path logDir = Path.of("/tmp/gt-backend-task9b-qa-child-logs");
        Files.createDirectories(logDir);
        Path logA = logDir.resolve("child-a-" + System.nanoTime() + ".log");
        Process childA = startServiceProcess(childAPort, logA);
        String acceptedBody = null;
        String acceptedKey = UUID.randomUUID().toString();
        String deletedAt = null;
        String purgeBy = null;
        UUID acceptedAccount = null;
        try {
            awaitProcessReady(childA, childAPort);
            ProofChallenge signupA = challengeVia(childAPort, "{}");
            Reply grantA = postTo(childAPort, "/v1/auth/google",
                    "{\"challengeId\":\"" + signupA.id + "\",\"idToken\":\"child-signup-a\"}", Map.of(
                            "Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, grantA.status, "child A log: " + logA + " " + grantA.body);
            acceptedAccount = UUID.fromString(grantA.node().path("account").path("id").asText());

            ProofChallenge deleteA = challengeVia(childAPort, "{\"purpose\":\"deleteAccount\"}");
            acceptedBody = "{\"accountId\":\"" + acceptedAccount + "\",\"challengeId\":\"" + deleteA.id
                    + "\",\"idToken\":\"child-delete-a\",\"confirm\":true}";
            Reply accepted = postTo(childAPort, "/v1/account/delete", acceptedBody,
                    Map.of("Idempotency-Key", acceptedKey));
            assertEquals(202, accepted.status, "child A log: " + logA + " " + accepted.body);
            deletedAt = accepted.node().path("deletedAt").asText();
            purgeBy = accepted.node().path("purgeBy").asText();

            childA.destroyForcibly();
            assertTrue(childA.waitFor(15, TimeUnit.SECONDS), "child A did not terminate");
            assertTrue(!childA.isAlive(), "child A must be dead before recovery");
            // Remove the original test challenge and credentials after A death to prove
            // B exact replay has no such dependency; the scoped ledger recovery is sufficient.
            UUID deleteChallenge = UUID.fromString(
                    JSON.readTree(acceptedBody).path("challengeId").asText());
            jdbc.update("DELETE FROM auth_challenges WHERE id=?", deleteChallenge);
        } finally {
            if (childA.isAlive()) {
                childA.destroyForcibly();
                childA.waitFor(15, TimeUnit.SECONDS);
            }
        }

        int childBPort = freePort();
        Path logB = logDir.resolve("child-b-" + System.nanoTime() + ".log");
        Process childB = startServiceProcess(childBPort, logB);
        try {
            awaitProcessReady(childB, childBPort);
            assertNotNull(acceptedBody);
            Reply recovered = postTo(childBPort, "/v1/account/delete", acceptedBody,
                    Map.of("Idempotency-Key", acceptedKey));
            assertEquals(202, recovered.status, "child B log: " + logB + " " + recovered.body);
            assertEquals(acceptedAccount.toString(), recovered.node().path("accountId").asText());
            assertEquals(deletedAt, recovered.node().path("deletedAt").asText());
            assertEquals(purgeBy, recovered.node().path("purgeBy").asText());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                    acceptedAccount));
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
    void v1AcceptanceSurvivesRestartedV2WithRetainedV1AndMissingKeyFailsClosed() throws Exception {
        Path logDir = Path.of("/tmp/gt-backend-task9b-qa-child-logs");
        Files.createDirectories(logDir);
        int portA = freePort();
        Path logA = logDir.resolve("rollover-a-" + System.nanoTime() + ".log");
        Process childA = startServiceProcess(portA, logA, SYNTHETIC_RING);
        String bodyV1 = null;
        String keyV1 = UUID.randomUUID().toString();
        String deletedAt = null;
        String purgeBy = null;
        UUID oldAccount = null;
        try {
            awaitProcessReady(childA, portA);
            ProofChallenge signupA = challengeVia(portA, "{}");
            Reply grantA = postTo(portA, "/v1/auth/google",
                    "{\"challengeId\":\"" + signupA.id + "\",\"idToken\":\"rollover-signup\"}", Map.of(
                            "Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, grantA.status, "child A log: " + logA + " " + grantA.body);
            oldAccount = UUID.fromString(grantA.node().path("account").path("id").asText());
            ProofChallenge deleteA = challengeVia(portA, "{\"purpose\":\"deleteAccount\"}");
            bodyV1 = "{\"accountId\":\"" + oldAccount + "\",\"challengeId\":\"" + deleteA.id
                    + "\",\"idToken\":\"rollover-delete-v1\",\"confirm\":true}";
            Reply accepted = postTo(portA, "/v1/account/delete", bodyV1, Map.of("Idempotency-Key", keyV1));
            assertEquals(202, accepted.status, "child A log: " + logA + " " + accepted.body);
            deletedAt = accepted.node().path("deletedAt").asText();
            purgeBy = accepted.node().path("purgeBy").asText();
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
        Path logB = logDir.resolve("rollover-b-" + System.nanoTime() + ".log");
        Process childB = startServiceProcess(portB, logB, SYNTHETIC_RING_V1_V2);
        UUID v2Account = null;
        try {
            awaitProcessReady(childB, portB);
            assertNotNull(bodyV1);
            Reply recovered = postTo(portB, "/v1/account/delete", bodyV1, Map.of("Idempotency-Key", keyV1));
            assertEquals(202, recovered.status, "child B log: " + logB + " " + recovered.body);
            assertEquals(deletedAt, recovered.node().path("deletedAt").asText());
            assertEquals(purgeBy, recovered.node().path("purgeBy").asText());
            // Fresh old-A status through B recovers the original receipt with stored v1.
            Reply statusChallengeB = postTo(portB, "/v1/auth/google/challenge",
                    "{\"purpose\":\"accountStatus\"}", null);
            assertEquals(200, statusChallengeB.status, statusChallengeB.body);
            String statusIdB = statusChallengeB.node().path("challengeId").asText();
            Reply statusB = postTo(portB, "/v1/account/status",
                    "{\"accountId\":\"" + oldAccount + "\",\"challengeId\":\"" + statusIdB
                            + "\",\"idToken\":\"rollover-status-b\"}", null);
            assertEquals(200, statusB.status, "child B log: " + logB + " " + statusB.body);
            assertEquals(deletedAt, statusB.node().path("deletedAt").asText());
            // Actual v2 acceptance for a new post-deletion account records version 2.
            Reply signupB = postTo(portB, "/v1/auth/google/challenge", "{}", null);
            assertEquals(200, signupB.status, signupB.body);
            Reply grantB = postTo(portB, "/v1/auth/google",
                    "{\"challengeId\":\"" + signupB.node().path("challengeId").asText()
                            + "\",\"idToken\":\"rollover-signup-b\"}",
                    Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
            assertEquals(200, grantB.status, "child B log: " + logB + " " + grantB.body);
            v2Account = UUID.fromString(grantB.node().path("account").path("id").asText());
            Reply deleteChallengeB = postTo(portB, "/v1/auth/google/challenge",
                    "{\"purpose\":\"deleteAccount\"}", null);
            assertEquals(200, deleteChallengeB.status, deleteChallengeB.body);
            Reply acceptedV2 = postTo(portB, "/v1/account/delete",
                    "{\"accountId\":\"" + v2Account + "\",\"challengeId\":\""
                            + deleteChallengeB.node().path("challengeId").asText()
                            + "\",\"idToken\":\"rollover-delete-v2\",\"confirm\":true}",
                    Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
            assertEquals(202, acceptedV2.status, "child B log: " + logB + " " + acceptedV2.body);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT request_digest_version FROM account_deletion_ledger WHERE old_account_id=?",
                    Integer.class, v2Account));
        } finally {
            if (childB.isAlive()) {
                childB.destroy();
                if (!childB.waitFor(15, TimeUnit.SECONDS)) {
                    childB.destroyForcibly();
                    childB.waitFor(15, TimeUnit.SECONDS);
                }
            }
        }
        int portC = freePort();
        Path logC = logDir.resolve("rollover-c-" + System.nanoTime() + ".log");
        Process childC = startServiceProcess(portC, logC, SYNTHETIC_RING_V2_ONLY);
        try {
            awaitProcessReady(childC, portC);
            assertNotNull(bodyV1);
            Reply missing = postTo(portC, "/v1/account/delete", bodyV1, Map.of("Idempotency-Key", keyV1));
            assertEquals(503, missing.status, "child C log: " + logC + " " + missing.body);
            assertEquals("temporarily_unavailable", missing.node().path("errorCode").asText());
            Reply statusChallengeC = postTo(portC, "/v1/auth/google/challenge",
                    "{\"purpose\":\"accountStatus\"}", null);
            assertEquals(200, statusChallengeC.status, statusChallengeC.body);
            Reply statusMissing = postTo(portC, "/v1/account/status",
                    "{\"accountId\":\"" + oldAccount + "\",\"challengeId\":\""
                            + statusChallengeC.node().path("challengeId").asText()
                            + "\",\"idToken\":\"rollover-status-c\"}", null);
            assertEquals(503, statusMissing.status, "child C log: " + logC + " " + statusMissing.body);
            Reply signupMissing = postTo(portC, "/v1/auth/google/challenge", "{}", null);
            assertEquals(200, signupMissing.status, signupMissing.body);
            Reply signupClosed = postTo(portC, "/v1/auth/google",
                    "{\"challengeId\":\"" + signupMissing.node().path("challengeId").asText()
                            + "\",\"idToken\":\"rollover-signup-c\"}",
                    Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
            assertEquals(503, signupClosed.status, "child C log: " + logC + " " + signupClosed.body);
        } finally {
            if (childC.isAlive()) {
                childC.destroy();
                if (!childC.waitFor(15, TimeUnit.SECONDS)) {
                    childC.destroyForcibly();
                    childC.waitFor(15, TimeUnit.SECONDS);
                }
            }
            // Isolate v2 fixture rows from unrelated parent v1 signup contexts in this database.
            try {
                if (v2Account != null) {
                    jdbc.update("DELETE FROM account_purge_intents WHERE old_account_id=?", v2Account);
                    jdbc.update("DELETE FROM account_deletion_ledger WHERE old_account_id=?", v2Account);
                }
            } catch (Exception ignored) {
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

    private Process startServiceProcess(int childPort, Path log) {
        return startServiceProcess(childPort, log, SYNTHETIC_RING);
    }

    private Process startServiceProcess(int childPort, Path log, String ring) {
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
            env.put("GOOGLE_OAUTH_AUDIENCE", "deletion-restart-audience");
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            env.put("ACCOUNT_DELETION_LEDGER_KEYS", ring);
            env.put("JAVA_TOOL_OPTIONS", "-Dtask9b.child-google.enabled=true "
                    + "-Dtask9b.child-purge-capable=true");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            return builder.start();
        } catch (Exception e) {
            throw new AssertionError("Could not start test-owned service process", e);
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
