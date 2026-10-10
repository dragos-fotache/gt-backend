package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9b.4: authenticated status and minimization.
 *
 * <p>Status never creates an account or grant. Live ownership resolves through the
 * current identity binding; deleted ownership resolves through permanent keyed
 * evidence, including old account A after the same Google identity owns new account
 * B. Raw provider material and domain payloads never belong in the ledger. Google
 * verification is mocked at the external boundary; PostgreSQL is isolated
 * Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountStatusHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String RING_V1 =
            "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}";
    private static final String RING_V1_V2 =
            "{\"activeVersion\":\"2\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\","
                    + "\"2\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\"}}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "status-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> RING_V1);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired
    MutableClock statusClock;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now());

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
    static class StatusClockConfiguration {
        @Bean
        @Primary
        MutableClock statusClock() {
            return new MutableClock();
        }
    }

    private record BoundAccount(UUID accountId, String subject) {}
    private record ProofChallenge(String id, String nonce) {}
    private record Reply(int status, String body, Map<String, List<String>> headers) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body, e);
            }
        }

        String cacheControl() {
            for (var entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Cache-Control")
                        && !entry.getValue().isEmpty()) {
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
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject);
    }

    private Reply statusCall(String accountId, String challengeId, String token) {
        return post("/v1/account/status",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId
                        + "\",\"idToken\":\"" + token + "\"}", null);
    }

    private Reply deleteCall(String accountId, String challengeId, String token, String key) {
        return post("/v1/account/delete",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId
                        + "\",\"idToken\":\"" + token + "\",\"confirm\":true}",
                Map.of("Idempotency-Key", key));
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    @Test
    void missingHistoricalVersionFailsClosedForNewSignupRatherThanSilentNoHistory() {
        BoundAccount old = provision("history-omission-subject", "history-omission-a");
        ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("history-omission-delete", deleteProof.nonce))
                .thenReturn(new Verified("history-omission-subject", null));
        Reply deleted = deleteCall(old.accountId.toString(), deleteProof.id, "history-omission-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);
        // Simulate a required historical version omitted from the configured ring by
        // rewriting the stored subject version to an unconfigured value, then restore
        // in finally so global completeness does not poison later tests in this database.
        jdbc.update("UPDATE account_deletion_ledger SET subject_digest_version=2 WHERE old_account_id=?",
                old.accountId);
        try {
            ProofChallenge freshSignIn = challenge("{}");
            lenient().when(verifier.verify("history-omission-fresh", freshSignIn.nonce))
                    .thenReturn(new Verified("history-omission-subject", null));
            Reply fenced = post("/v1/auth/google",
                    "{\"challengeId\":\"" + freshSignIn.id + "\",\"idToken\":\"history-omission-fresh\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(503, fenced.status, fenced.body);
            assertEquals("temporarily_unavailable", fenced.node().path("errorCode").asText());
        } finally {
            jdbc.update("UPDATE account_deletion_ledger SET subject_digest_version=1 WHERE old_account_id=?",
                    old.accountId);
        }
    }

    @Test
    void activeStatusReturnsLiveAccountAndConsumesOnlySuccessfulProof() {
        BoundAccount account = provision("status-active-subject", "status-active-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-active-token", proof.nonce))
                .thenReturn(new Verified("status-active-subject", null));
        Reply reply = statusCall(account.accountId.toString(), proof.id, "status-active-token");
        assertEquals(200, reply.status, reply.body);
        assertEquals("no-store", reply.cacheControl());
        assertEquals(account.accountId.toString(), reply.node().path("accountId").asText());
        assertEquals("active", reply.node().path("state").asText());
        assertTrue(challengeConsumed(proof.id));

        // A lost status response requires another challenge, not stored replay.
        Reply lost = statusCall(account.accountId.toString(), proof.id, "status-active-token");
        assertEquals(401, lost.status, lost.body);
    }

    @Test
    void statusProofExpiringDuringAccountLockWaitIsRejectedWithoutConsumption() throws Exception {
        BoundAccount account = provision("status-account-wait-subject", "status-account-wait-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-account-wait-token", proof.nonce))
                .thenReturn(new Verified("status-account-wait-subject", null));
        java.sql.Timestamp expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?",
                java.sql.Timestamp.class, UUID.fromString(proof.id));
        assertNotNull(expires);
        var txStatus = txManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        Integer harnessPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            jdbc.query("SELECT deleted_at FROM accounts WHERE id=? FOR UPDATE", rs -> null, account.accountId);
            java.util.concurrent.Future<Reply> pending = pool.submit(
                    () -> statusCall(account.accountId.toString(), proof.id, "status-account-wait-token"));
            assertTrue(awaitPgBlocking(harnessPid), "status never waited on harness account lock");
            statusClock.set(expires.toInstant());
            txManager.commit(txStatus);
            txStatus = null;
            Reply reply = pending.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(401, reply.status, reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertFalse(challengeConsumed(proof.id));
            assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                    Boolean.class, account.accountId));
        } finally {
            statusClock.set(Instant.now());
            if (txStatus != null && !txStatus.isCompleted()) {
                txManager.rollback(txStatus);
            }
            pool.shutdownNow();
        }
    }

    private boolean awaitPgBlocking(int blockingPid) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted AND pg_blocking_pids(pid) @> ARRAY[?]",
                    Integer.class, blockingPid);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    @Test
    void unknownAndForeignStatusDoNotConsumeAndCreateNothing() {
        BoundAccount account = provision("status-known-subject", "status-known-signup");
        int accountsBefore = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);
        int sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class);
        ProofChallenge unknown = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-unknown-token", unknown.nonce))
                .thenReturn(new Verified("status-known-subject", null));
        Reply unknownReply = statusCall(UUID.randomUUID().toString(), unknown.id, "status-unknown-token");
        assertEquals(404, unknownReply.status, unknownReply.body);
        assertFalse(challengeConsumed(unknown.id));

        BoundAccount other = provision("status-other-subject", "status-other-signup");
        ProofChallenge foreign = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-foreign-token", foreign.nonce))
                .thenReturn(new Verified("status-other-subject", null));
        Reply foreignReply = statusCall(account.accountId.toString(), foreign.id, "status-foreign-token");
        assertEquals(404, foreignReply.status, foreignReply.body);
        assertFalse(challengeConsumed(foreign.id));
        assertEquals(accountsBefore + 1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        assertEquals(sessionsBefore + 1, jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class));
        assertNotEquals(account.accountId, other.accountId);
    }

    @Test
    void deletedStatusRecoversOriginalReceiptWhileNewAccountOwnsSameSubject() {
        BoundAccount old = provision("status-resignup-subject", "status-resignup-a");
        ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("status-delete-token", deleteProof.nonce))
                .thenReturn(new Verified("status-resignup-subject", null));
        Reply deleted = deleteCall(old.accountId.toString(), deleteProof.id, "status-delete-token",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);
        String deletedAt = deleted.node().path("deletedAt").asText();
        String purgeBy = deleted.node().path("purgeBy").asText();

        // A distinct fresh sign-in creates empty B with a new UUID after A is detached.
        // The signup fence requires a challenge strictly after the recorded deletion: advance
        // the fixed test clock past the deletion instant before the fresh signup challenge.
        statusClock.set(statusClock.instant().plusMillis(1));
        ProofChallenge signup = challenge("{}");
        lenient().when(verifier.verify("status-resignup-b", signup.nonce))
                .thenReturn(new Verified("status-resignup-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"status-resignup-b\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        UUID fresh = UUID.fromString(grant.node().path("account").path("id").asText());
        assertNotEquals(old.accountId, fresh);

        // Old A status still identifies A with the original receipt, not B.
        ProofChallenge oldStatus = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-old-a", oldStatus.nonce))
                .thenReturn(new Verified("status-resignup-subject", null));
        Reply oldReply = statusCall(old.accountId.toString(), oldStatus.id, "status-old-a");
        assertEquals(200, oldReply.status, oldReply.body);
        assertEquals(old.accountId.toString(), oldReply.node().path("accountId").asText());
        assertEquals("deleted", oldReply.node().path("state").asText());
        assertEquals(deletedAt, oldReply.node().path("deletedAt").asText());
        assertEquals(purgeBy, oldReply.node().path("purgeBy").asText());

        // Fresh status for B reports active and does not clear or confuse A.
        ProofChallenge freshStatus = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("status-fresh-b", freshStatus.nonce))
                .thenReturn(new Verified("status-resignup-subject", null));
        Reply freshReply = statusCall(fresh.toString(), freshStatus.id, "status-fresh-b");
        assertEquals(200, freshReply.status, freshReply.body);
        assertEquals("active", freshReply.node().path("state").asText());
        assertEquals(2, List.of(oldReply, freshReply).size());
    }

    @Test
    void statusProofExpiringDuringProviderWaitIsRejectedWithoutConsumption() throws Exception {
        BoundAccount account = provision("status-wait-subject", "status-wait-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"accountStatus\"}");
        java.sql.Timestamp expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?",
                java.sql.Timestamp.class, UUID.fromString(proof.id));
        assertNotNull(expires);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        lenient().when(verifier.verify("status-wait-token", proof.nonce)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(60, java.util.concurrent.TimeUnit.SECONDS), "provider wait not released");
            return new Verified("status-wait-subject", null);
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Reply> pending = pool.submit(
                    () -> statusCall(account.accountId.toString(), proof.id, "status-wait-token"));
            assertTrue(entered.await(60, java.util.concurrent.TimeUnit.SECONDS), "status never reached provider");
            jdbc.update("UPDATE auth_challenges SET expires_at=clock_timestamp() - interval '1 second' WHERE id=?",
                    UUID.fromString(proof.id));
            statusClock.set(Instant.now().plusSeconds(300));
            release.countDown();
            Reply reply = pending.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(401, reply.status, reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertFalse(challengeConsumed(proof.id));
        } finally {
            statusClock.set(Instant.now());
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void agedLedgerOnlySupplementWithIndependentHmacsRecoversWhileBOwnsSubject() throws Exception {
        UUID agedAccount = UUID.randomUUID();
        UUID agedKey = UUID.randomUUID();
        String agedSubject = "aged-ledger-supplement-subject";
        String fixedChallenge = UUID.randomUUID().toString();
        String agedBody = "{\"accountId\":\"" + agedAccount + "\",\"challengeId\":\"" + fixedChallenge
                + "\",\"idToken\":\"aged-ledger-token\",\"confirm\":true}";
        byte[] ringKey = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        byte[] subjectDigest = independentSubjectHmac(ringKey, 1, agedSubject);
        byte[] requestDigest = independentRequestHmac(ringKey, 1, agedAccount, agedKey,
                agedBody.getBytes(StandardCharsets.UTF_8));
        java.sql.Timestamp deletedAt = java.sql.Timestamp.from(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        java.sql.Timestamp purgeBy = java.sql.Timestamp.from(java.time.Instant.parse("2020-01-02T00:00:00Z"));
        jdbc.update("INSERT INTO account_deletion_ledger(old_account_id,subject_digest_version,"
                + "subject_digest,deleted_at,purge_by,deletion_key,request_digest_version,request_digest)"
                + " VALUES (?,?,?, ?,?,?,?,?)", agedAccount, 1, subjectDigest, deletedAt, purgeBy,
                agedKey, 1, requestDigest);
        jdbc.update("INSERT INTO account_purge_intents(old_account_id,created_at,purge_by,state)"
                + " VALUES (?,?,?,'pending')", agedAccount, deletedAt, purgeBy);
        try {
            BoundAccount freshB = provision(agedSubject, "aged-ledger-signup-b");
            assertNotEquals(agedAccount, freshB.accountId);
            ProofChallenge statusProof = challenge("{\"purpose\":\"accountStatus\"}");
            lenient().when(verifier.verify("aged-ledger-status", statusProof.nonce))
                    .thenReturn(new Verified(agedSubject, null));
            Reply status = statusCall(agedAccount.toString(), statusProof.id, "aged-ledger-status");
            assertEquals(200, status.status, status.body);
            assertEquals(agedAccount.toString(), status.node().path("accountId").asText());
            assertEquals("deleted", status.node().path("state").asText());
            assertEquals("2020-01-01T00:00:00Z", status.node().path("deletedAt").asText());
            assertEquals("2020-01-02T00:00:00Z", status.node().path("purgeBy").asText());
            ProofChallenge differentProof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("aged-ledger-different", differentProof.nonce))
                    .thenReturn(new Verified(agedSubject, null));
            Reply different = post("/v1/account/delete",
                    "{\"accountId\":\"" + agedAccount + "\",\"challengeId\":\"" + differentProof.id
                            + "\",\"idToken\":\"aged-ledger-different\",\"confirm\":true}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(409, different.status, different.body);
            assertEquals("invalid_state", different.node().path("errorCode").asText());
            Reply replay = post("/v1/account/delete", agedBody, Map.of("Idempotency-Key", agedKey.toString()));
            assertEquals(202, replay.status, replay.body);
            assertEquals("2020-01-01T00:00:00Z", replay.node().path("deletedAt").asText());
            assertEquals("2020-01-02T00:00:00Z", replay.node().path("purgeBy").asText());
        } finally {
            jdbc.update("DELETE FROM account_purge_intents WHERE old_account_id=?", agedAccount);
            jdbc.update("DELETE FROM account_deletion_ledger WHERE old_account_id=?", agedAccount);
        }
    }

    private static byte[] independentSubjectHmac(byte[] key, int version, String subject) throws Exception {
        byte[] framed = concatForHmac(framedForHmac("GT-DELETION-SUBJECT-V1"),
                java.nio.ByteBuffer.allocate(4).putInt(version).array(), framedForHmac("google"),
                framedForHmac(subject));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(framed);
    }

    private static byte[] independentRequestHmac(byte[] key, int version, UUID accountId, UUID keyId,
                                                 byte[] exactBody) throws Exception {
        byte[] framed = concatForHmac(framedForHmac("GT-DELETION-REQUEST-V1"),
                java.nio.ByteBuffer.allocate(4).putInt(version).array(), framedForHmac(accountId.toString()),
                framedForHmac("deletion"), framedForHmac(keyId.toString()), framedForHmac(exactBody));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(framed);
    }

    private static byte[] framedForHmac(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(4 + raw.length).putInt(raw.length).put(raw).array();
    }

    private static byte[] framedForHmac(byte[] raw) {
        return java.nio.ByteBuffer.allocate(4 + raw.length).putInt(raw.length).put(raw).array();
    }

    private static byte[] concatForHmac(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    @Test
    void originalReceiptRecoveryHasNoTtlWithoutAgeFabrication() {
        BoundAccount old = provision("ninety-day-subject", "ninety-day-a");
        ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("ninety-day-delete", deleteProof.nonce))
                .thenReturn(new Verified("ninety-day-subject", null));
        Reply deleted = deleteCall(old.accountId.toString(), deleteProof.id, "ninety-day-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);
        String deletedAt = deleted.node().path("deletedAt").asText();
        String purgeBy = deleted.node().path("purgeBy").asText();
        // Original immutable columns and receipt are never rewritten to fabricate age;
        // status recovers the exact original receipt, proving no TTL on minimal evidence.
        ProofChallenge statusProof = challenge("{\"purpose\":\"accountStatus\"}");
        lenient().when(verifier.verify("ninety-day-status", statusProof.nonce))
                .thenReturn(new Verified("ninety-day-subject", null));
        Reply status = statusCall(old.accountId.toString(), statusProof.id, "ninety-day-status");
        assertEquals(200, status.status, status.body);
        assertEquals("deleted", status.node().path("state").asText());
        assertEquals(old.accountId.toString(), status.node().path("accountId").asText());
        assertEquals(deletedAt, status.node().path("deletedAt").asText());
        assertEquals(purgeBy, status.node().path("purgeBy").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                old.accountId));
    }

    @Test
    void ledgerContainsOnlyWhitelistAndNoCredentialOrDomainCopies() {
        BoundAccount account = provision("status-minimize-subject", "status-minimize-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("status-minimize-token", proof.nonce))
                .thenReturn(new Verified("status-minimize-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), proof.id, "status-minimize-token",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);

        List<String> columns = jdbc.query(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name='account_deletion_ledger' ORDER BY 1",
                (rs, row) -> rs.getString(1));
        for (String forbidden : List.of("id_token", "raw_body", "raw_token", "email", "display_name",
                "access_token", "refresh_token", "export_payload", "domain_payload", "sync_response")) {
            assertFalse(columns.contains(forbidden), "ledger must not retain " + forbidden);
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM account_deletion_ledger WHERE old_account_id=?", account.accountId);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() instanceof String text) {
                assertFalse(text.contains("status-minimize-token"), "ledger must not embed raw token");
                assertFalse(text.contains("status-minimize-subject"), "ledger must not embed raw subject");
            }
        }
        assertNotNull(row.get("subject_digest"));
        assertNotNull(row.get("request_digest"));
        assertNotNull(RING_V1);
        assertNotNull(RING_V1_V2);
    }
}
