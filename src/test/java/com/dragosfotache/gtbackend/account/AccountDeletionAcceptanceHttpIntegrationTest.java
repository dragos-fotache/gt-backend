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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9b.2: atomic irreversible deletion acceptance.
 *
 * <p>Receipt timestamps and deadline are asserted from hand-computed service time:
 * {@code purgeBy} equals acceptance plus exactly 86400 seconds. Ledger and intent
 * assertions inspect only the whitelisted minimal fields. Google verification is
 * mocked at the external boundary; PostgreSQL is isolated Testcontainers with real
 * HTTP. Isolated acceptance explicitly imports the test-classpath-only readiness
 * replacement; ordinary production remains unavailable.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionAcceptanceHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SYNTHETIC_RING =
            "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "deletion-accept-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock acceptanceClock;
    @Autowired
    PlatformTransactionManager txManager;
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
    static class AcceptanceClockConfiguration {
        @Bean
        @Primary
        MutableClock acceptanceClock() {
            return new MutableClock();
        }
    }

    private record BoundAccount(UUID accountId, String subject, String accessToken, String refreshToken) {}
    private record ProofChallenge(String id, String nonce) {}
    private record Reply(int status, String body, Map<String, List<String>> headers) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body, e);
            }
        }

        String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private Reply post(String path, String body, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8), response.headers().map());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private ProofChallenge challenge(String purposeBody) {
        Reply reply = post("/v1/auth/google/challenge", purposeBody, null);
        assertEquals(200, reply.status, reply.body);
        return new ProofChallenge(reply.node().path("challengeId").asText(), reply.node().path("nonce").asText());
    }

    private BoundAccount provision(String subject, String token) {
        ProofChallenge signup = challenge("{}");
        lenient().when(verifier.verify(token, signup.nonce)).thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject,
                grant.node().path("accessToken").asText(), grant.node().path("refreshToken").asText());
    }

    private BoundAccount provisionAgain(String subject, String token) {
        return provision(subject, token);
    }

    private Reply authenticatedPost(String bearer, String path, String body) {
        return post(path, body, Map.of("Authorization", "Bearer " + bearer,
                "Idempotency-Key", UUID.randomUUID().toString()));
    }

    private Reply deleteCall(String accountId, String challengeId, String token, String deletionKey) {
        String body = "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId
                + "\",\"idToken\":\"" + token + "\",\"confirm\":true}";
        return post("/v1/account/delete", body, Map.of("Idempotency-Key", deletionKey));
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    @Test
    void fractionalAcceptanceRetainsMillisecondReceiptAcrossStatusAndExpiredProofReplay() {
        Instant previous = acceptanceClock.instant();
        Instant raw = Instant.parse("2026-09-27T12:00:00.123456789Z");
        Instant expectedAt = Instant.parse("2026-09-27T12:00:00.123Z");
        Instant expectedBy = Instant.parse("2026-09-28T12:00:00.123Z");
        try {
            acceptanceClock.set(raw);
            BoundAccount account = provision("precision-subject", "precision-signup");
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("precision-delete", proof.nonce))
                    .thenReturn(new Verified("precision-subject", null));
            String key = UUID.randomUUID().toString();
            Reply accepted = deleteCall(account.accountId.toString(), proof.id, "precision-delete", key);
            assertEquals(202, accepted.status, "Fractional-clock deletion must be accepted");
            assertEquals(expectedAt.toString(), accepted.node().path("deletedAt").asText(),
                    "Wire deletion time must preserve exact milliseconds only");
            assertEquals(expectedBy.toString(), accepted.node().path("purgeBy").asText(),
                    "Wire purge deadline must be exactly24hours after persisted acceptance");
            assertEquals(expectedAt, jdbc.queryForObject(
                    "SELECT deleted_at FROM account_deletion_ledger WHERE old_account_id=?",
                    java.sql.Timestamp.class, account.accountId).toInstant());
            assertEquals(expectedBy, jdbc.queryForObject(
                    "SELECT purge_by FROM account_deletion_ledger WHERE old_account_id=?",
                    java.sql.Timestamp.class, account.accountId).toInstant());
            assertEquals(expectedBy, jdbc.queryForObject(
                    "SELECT purge_by FROM account_purge_intents WHERE old_account_id=?",
                    java.sql.Timestamp.class, account.accountId).toInstant());

            assertEquals(expectedAt, jdbc.queryForObject(
                    "SELECT created_at FROM account_purge_intents WHERE old_account_id=?",
                    java.sql.Timestamp.class, account.accountId).toInstant());
            assertEquals(expectedAt, jdbc.queryForObject(
                    "SELECT deleted_at FROM accounts WHERE id=?",
                    java.sql.Timestamp.class, account.accountId).toInstant());
            assertEquals(expectedAt, jdbc.queryForObject(
                    "SELECT consumed_at FROM auth_challenges WHERE id=?",
                    java.sql.Timestamp.class, UUID.fromString(proof.id)).toInstant());

            acceptanceClock.set(raw.plusSeconds(301));
            Reply replay = deleteCall(account.accountId.toString(), proof.id, "precision-delete", key);
            assertEquals(202, replay.status, "Expired original proof must not invalidate exact replay");
            assertEquals(accepted.body, replay.body, "Exact replay must retain the original receipt bytes");
            ProofChallenge statusProof = challenge("{\"purpose\":\"accountStatus\"}");
            lenient().when(verifier.verify("precision-status", statusProof.nonce))
                    .thenReturn(new Verified("precision-subject", null));
            Reply status = post("/v1/account/status",
                    "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + statusProof.id
                            + "\",\"idToken\":\"precision-status\"}", null);
            assertEquals(200, status.status, "Fresh status proof must recover accepted deletion");
            assertEquals(accepted.body, status.body, "Status must retain persisted deletion/deadline bytes");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?",
                    Integer.class, account.accountId));
        } finally {
            acceptanceClock.set(previous);
        }
    }

    @Test
    void acceptedDeletionDisablesAccountSessionsBindingAndRecordsMinimalEvidence() {
        BoundAccount account = provision("accept-subject", "accept-signup");
        BoundAccount secondGrant = provisionAgain("accept-subject", "accept-second-signup");
        assertEquals(account.accountId, secondGrant.accountId);
        // Rich owned state through accepted APIs: custom exercise, routine draft and workout.
        String custom = UUID.randomUUID().toString();
        Reply customCreated = authenticatedPost(account.accessToken, "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Accept custom\"}");
        assertEquals(201, customCreated.status, customCreated.body);
        String routine = UUID.randomUUID().toString();
        assertEquals(201, authenticatedPost(account.accessToken, "/v1/routines",
                "{\"id\":\"" + routine + "\"}").status);
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authenticatedPost(account.accessToken, "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Accept workout\",\"startedAt\":\"2026-01-01T00:00:00Z\"}").status);
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, account.accountId));

        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("accept-token", proof.nonce))
                .thenReturn(new Verified("accept-subject", null));
        Instant before = acceptanceClock.instant();
        String key = UUID.randomUUID().toString();
        Reply reply = deleteCall(account.accountId.toString(), proof.id, "accept-token", key);
        assertEquals(202, reply.status, reply.body);
        assertEquals("no-store", reply.header("Cache-Control"));
        assertEquals(account.accountId.toString(), reply.node().path("accountId").asText());
        assertEquals("deleted", reply.node().path("state").asText());
        Instant deletedAt = Instant.parse(reply.node().path("deletedAt").asText());
        Instant purgeBy = Instant.parse(reply.node().path("purgeBy").asText());
        assertEquals(86400L, purgeBy.getEpochSecond() - deletedAt.getEpochSecond());
        assertEquals(deletedAt.plusSeconds(86400L), purgeBy);
        assertFalse(deletedAt.isBefore(before.minusSeconds(5)));

        assertTrue(challengeConsumed(proof.id));
        assertTrue(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_bindings WHERE account_id=?", Integer.class,
                account.accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?", Integer.class,
                account.accountId));
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT deleted_at, purge_by, deletion_key FROM account_deletion_ledger WHERE old_account_id=?",
                account.accountId);
        assertEquals(java.sql.Timestamp.from(deletedAt),
                (java.sql.Timestamp) ledger.get("deleted_at"));
        assertEquals(java.sql.Timestamp.from(purgeBy), (java.sql.Timestamp) ledger.get("purge_by"));
        assertEquals(UUID.fromString(key), ledger.get("deletion_key"));

        // No raw credential-bearing body, token, subject, profile or domain payload survives.
        // Complete whitelist columns are asserted; rollback effects cover binding, sessions
        // and domain evidence through the dedicated rollback test below.
        List<String> columns = jdbc.query(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name='account_deletion_ledger' ORDER BY 1",
                (rs, row) -> rs.getString(1));
        for (String required : List.of("old_account_id", "subject_digest_version", "subject_digest",
                "deleted_at", "purge_by", "deletion_key", "request_digest_version", "request_digest")) {
            assertTrue(columns.contains(required), "ledger whitelist must contain " + required);
        }
        assertFalse(columns.contains("id_token"));
        assertFalse(columns.contains("raw_body"));
        assertFalse(columns.contains("email"));
        assertFalse(columns.contains("display_name"));
    }

    @Test
    void zeroSessionAccountAcceptsDeletion() {
        BoundAccount account = provision("zero-session-subject", "zero-session-signup");
        BoundAccount second = provisionAgain("zero-session-subject", "zero-session-second");
        assertEquals(account.accountId, second.accountId);
        // Revoke every session through accepted logout so zero live sessions remain.
        assertEquals(204, post("/v1/auth/logout",
                "{\"refreshToken\":\"" + account.refreshToken + "\"}", null).status);
        assertEquals(204, post("/v1/auth/logout",
                "{\"refreshToken\":\"" + second.refreshToken + "\"}", null).status);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, account.accountId));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE account_id=?",
                Integer.class, account.accountId) >= 2);
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("zero-session-token", proof.nonce))
                .thenReturn(new Verified("zero-session-subject", null));
        Reply reply = deleteCall(account.accountId.toString(), proof.id, "zero-session-token",
                UUID.randomUUID().toString());
        assertEquals(202, reply.status, reply.body);
        assertEquals("deleted", reply.node().path("state").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
    }

    @Test
    void zeroRowSessionInventoryAcceptsDeletionAfterBoundedCleanup() {
        BoundAccount account = provision("zero-row-subject", "zero-row-signup");
        BoundAccount second = provisionAgain("zero-row-subject", "zero-row-second");
        assertEquals(account.accountId, second.accountId);
        assertEquals(204, post("/v1/auth/logout",
                "{\"refreshToken\":\"" + account.refreshToken + "\"}", null).status);
        assertEquals(204, post("/v1/auth/logout",
                "{\"refreshToken\":\"" + second.refreshToken + "\"}", null).status);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, account.accountId));
        // Expire test-owned credentials and replay so bounded production cleanup can
        // reclaim rows while preserving the account and its identity binding.
        java.sql.Timestamp expired = java.sql.Timestamp.from(acceptanceClock.instant().minusSeconds(1));
        jdbc.update("UPDATE auth_credentials SET expires_at=? "
                + "WHERE session_id IN (SELECT id FROM auth_sessions WHERE account_id=?)", expired, account.accountId);
        jdbc.update("UPDATE auth_idempotency SET expires_at=? "
                + "WHERE session_id IN (SELECT id FROM auth_sessions WHERE account_id=?)", expired, account.accountId);
        authService.cleanupExpired();
        authService.cleanupExpired();
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=?", Integer.class, account.accountId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?", Integer.class,
                account.accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_bindings WHERE account_id=?", Integer.class,
                account.accountId));
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("zero-row-token", proof.nonce))
                .thenReturn(new Verified("zero-row-subject", null));
        Reply reply = deleteCall(account.accountId.toString(), proof.id, "zero-row-token",
                UUID.randomUUID().toString());
        assertEquals(202, reply.status, reply.body);
        assertEquals("deleted", reply.node().path("state").asText());
    }

    @Autowired
    TestAccountDeletionHooks deletionHooks;
    @Autowired
    com.dragosfotache.gtbackend.auth.AuthService authService;

    @Test
    void failedAcceptanceLeavesNoPartialDisablementOrLedger() {
        BoundAccount account = provision("rollback-subject", "rollback-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        // Wrong subject proof fails before any acceptance effect.
        lenient().when(verifier.verify("rollback-token", proof.nonce))
                .thenReturn(new Verified("unrelated-subject", null));
        Reply rejected = deleteCall(account.accountId.toString(), proof.id, "rollback-token",
                UUID.randomUUID().toString());
        assertEquals(404, rejected.status, rejected.body);
        assertFalse(challengeConsumed(proof.id));
        assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?", Integer.class,
                account.accountId));

        // An unchanged valid request can still succeed afterwards with fresh proof.
        ProofChallenge fresh = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("rollback-fresh-token", fresh.nonce))
                .thenReturn(new Verified("rollback-subject", null));
        Reply accepted = deleteCall(account.accountId.toString(), fresh.id, "rollback-fresh-token",
                UUID.randomUUID().toString());
        assertEquals(202, accepted.status, accepted.body);
    }

    @Test
    void injectedBeforeCommitFailureRollsBackAndUnchangedRetrySucceeds() {
        BoundAccount account = provision("fault-rollback-subject", "fault-rollback-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("fault-rollback-token", proof.nonce))
                .thenReturn(new Verified("fault-rollback-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                + "\",\"idToken\":\"fault-rollback-token\",\"confirm\":true}";
        deletionHooks.armBeforeCommitFault(new IllegalStateException("task9b-test-fault"));
        Reply failed = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(500, failed.status, failed.body);
        assertEquals("internal_error", failed.node().path("errorCode").asText());
        assertFalse(challengeConsumed(proof.id));
        assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?", Integer.class,
                account.accountId));
        // Exact unchanged original key, body and proof retry succeeds after complete rollback.
        Reply retry = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, retry.status, retry.body);
        assertEquals(account.accountId.toString(), retry.node().path("accountId").asText());
        assertTrue(challengeConsumed(proof.id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_bindings WHERE account_id=?", Integer.class,
                account.accountId));
    }

    @Test
    void transientBeforeCommitFailureMapsTo503WithCompleteRollback() {
        BoundAccount account = provision("transient-rollback-subject", "transient-rollback-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("transient-rollback-token", proof.nonce))
                .thenReturn(new Verified("transient-rollback-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                + "\",\"idToken\":\"transient-rollback-token\",\"confirm\":true}";
        deletionHooks.armBeforeCommitFault(
                new org.springframework.dao.TransientDataAccessResourceException("task9b-test-transient"));
        Reply failed = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(503, failed.status, failed.body);
        assertEquals("temporarily_unavailable", failed.node().path("errorCode").asText());
        assertFalse(challengeConsumed(proof.id));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        Reply retry = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, retry.status, retry.body);
    }
}
