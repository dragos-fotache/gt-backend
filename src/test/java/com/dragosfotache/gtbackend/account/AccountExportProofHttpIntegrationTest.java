package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9a.1: purpose-bound fresh authorization for {@code POST /v1/account/export}.
 *
 * <p>Expected export records are hand-authored; HTTP and DB assertions here cover only
 * proof/binding behavior. Google verification is mocked at the external boundary;
 * PostgreSQL is an isolated Testcontainers instance with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportProofHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "export-proof-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock proofClock;
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
    static class ProofClockConfiguration {
        @Bean
        @Primary
        MutableClock proofClock() {
            return new MutableClock();
        }
    }

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
    private record ProofChallenge(String id, String nonce) {}
    private record Reply(int status, String body, byte[] bytes, Map<String, List<String>> headers) {
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

        String cacheControl() {
            return header("Cache-Control");
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
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), bytes, response.headers().map());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private ProofChallenge challenge(String purposeBody) {
        Reply reply = post("/v1/auth/google/challenge", purposeBody, null);
        assertEquals(200, reply.status, reply.body);
        assertEquals("no-store", reply.cacheControl());
        return new ProofChallenge(reply.node().path("challengeId").asText(), reply.node().path("nonce").asText());
    }

    private BoundAccount provision(String subject, String token) {
        ProofChallenge signup = challenge("{}");
        lenient().when(verifier.verify(token, signup.nonce)).thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        return new BoundAccount(
                UUID.fromString(grant.node().path("account").path("id").asText()),
                subject,
                grant.node().path("accessToken").asText());
    }

    private Reply exportCall(String accountId, String challengeId, String token, Map<String, String> extraHeaders) {
        return post("/v1/account/export",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"" + token + "\"}",
                extraHeaders);
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void defaultChallengeIsSignInAndLifecyclePurposesAreNotInterchangeable() {
        ProofChallenge def = challenge("{}");
        ProofChallenge signIn = challenge("{\"purpose\":\"signIn\"}");
        ProofChallenge export = challenge("{\"purpose\":\"export\"}");
        ProofChallenge deleteAccount = challenge("{\"purpose\":\"deleteAccount\"}");
        ProofChallenge status = challenge("{\"purpose\":\"accountStatus\"}");
        assertNotEquals(def.id, signIn.id);

        // An export-purpose challenge must not authorize signup exchange.
        lenient().when(verifier.verify("purpose-signup-token", export.nonce))
                .thenReturn(new Verified("purpose-signup-subject", null));
        Reply signup = post("/v1/auth/google",
                "{\"challengeId\":\"" + export.id + "\",\"idToken\":\"purpose-signup-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, signup.status, signup.body);
        assertEquals("invalid_google_credential", signup.node().path("errorCode").asText());
        assertEquals("no-store", signup.cacheControl());
        assertFalse(challengeConsumed(export.id), "wrong-purpose exchange must not consume the challenge");

        // A signIn-purpose challenge must not authorize export.
        BoundAccount account = provision("purpose-export-subject", "purpose-export-signup");
        lenient().when(verifier.verify("purpose-export-token", signIn.nonce))
                .thenReturn(new Verified("purpose-export-subject", null));
        Reply wrongPurpose = exportCall(account.accountId.toString(), signIn.id, "purpose-export-token", null);
        assertEquals(401, wrongPurpose.status, wrongPurpose.body);
        assertEquals("invalid_google_credential", wrongPurpose.node().path("errorCode").asText());
        assertEquals("no-store", wrongPurpose.cacheControl());
        assertFalse(challengeConsumed(signIn.id));

        // deleteAccount and accountStatus challenges must not authorize export either.
        lenient().when(verifier.verify("purpose-delete-token", deleteAccount.nonce))
                .thenReturn(new Verified("purpose-export-subject", null));
        assertEquals(401, exportCall(account.accountId.toString(), deleteAccount.id, "purpose-delete-token", null).status);
        lenient().when(verifier.verify("purpose-status-token", status.nonce))
                .thenReturn(new Verified("purpose-export-subject", null));
        assertEquals(401, exportCall(account.accountId.toString(), status.id, "purpose-status-token", null).status);
        assertFalse(challengeConsumed(deleteAccount.id));
        assertFalse(challengeConsumed(status.id));
    }

    @Test
    void correctExportProofSucceedsWithoutBearer() {
        BoundAccount account = provision("no-bearer-subject", "no-bearer-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("no-bearer-token", proof.nonce))
                .thenReturn(new Verified("no-bearer-subject", null));
        Reply reply = exportCall(account.accountId.toString(), proof.id, "no-bearer-token", null);
        assertEquals(200, reply.status, reply.body);
        assertEquals("no-store", reply.cacheControl());
        assertNotNull(reply.header("Content-Disposition"));
        assertTrue(reply.header("Content-Disposition").contains("gymtracka-export.json"));
        assertEquals(1, reply.node().path("exportVersion").asInt());
        assertEquals(account.accountId.toString(), reply.node().path("accountId").asText());
        assertTrue(reply.node().path("customExercises").isArray());
        assertTrue(reply.node().path("routines").isArray());
        assertTrue(reply.node().path("workouts").isArray());
        assertTrue(challengeConsumed(proof.id));
    }

    @Test
    void wrongSubjectProofIsNotFoundAndLeavesChallengeUnconsumed() {
        BoundAccount account = provision("owner-subject", "owner-signup");
        provision("other-subject", "other-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("foreign-token", proof.nonce))
                .thenReturn(new Verified("other-subject", null));
        Reply reply = exportCall(account.accountId.toString(), proof.id, "foreign-token", null);
        assertEquals(404, reply.status, reply.body);
        assertEquals("not_found", reply.node().path("errorCode").asText());
        assertEquals("no-store", reply.cacheControl());
        assertFalse(reply.body.contains("foreign-token"));
        assertFalse(challengeConsumed(proof.id), "foreign binding must not consume the challenge");
    }

    @Test
    void wrongAccountProofIsNotFoundAndLeavesChallengeUnconsumed() {
        BoundAccount account = provision("account-a-subject", "account-a-signup");
        BoundAccount other = provision("account-b-subject", "account-b-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("wrong-account-token", proof.nonce))
                .thenReturn(new Verified("account-a-subject", null));
        Reply reply = exportCall(other.accountId.toString(), proof.id, "wrong-account-token", null);
        assertEquals(404, reply.status, reply.body);
        assertEquals("not_found", reply.node().path("errorCode").asText());
        assertFalse(challengeConsumed(proof.id));
        assertNotEquals(account.accountId, other.accountId);
    }

    @Test
    void unknownAccountProofCreatesNoAccountOrGrant() {
        BoundAccount account = provision("known-subject", "known-signup");
        int accountsBefore = count("SELECT count(*) FROM accounts");
        int bindingsBefore = count("SELECT count(*) FROM google_identity_bindings");
        int sessionsBefore = count("SELECT count(*) FROM auth_sessions");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("unknown-account-token", proof.nonce))
                .thenReturn(new Verified("known-subject", null));
        String unknown = UUID.randomUUID().toString();
        Reply reply = exportCall(unknown, proof.id, "unknown-account-token", null);
        assertEquals(404, reply.status, reply.body);
        assertEquals(accountsBefore, count("SELECT count(*) FROM accounts"));
        assertEquals(bindingsBefore, count("SELECT count(*) FROM google_identity_bindings"));
        assertEquals(sessionsBefore, count("SELECT count(*) FROM auth_sessions"));
        assertFalse(challengeConsumed(proof.id));
        assertNotEquals(unknown, account.accountId.toString());
    }

    @Test
    void unknownSubjectProofCreatesNoAccountOrGrant() {
        int accountsBefore = count("SELECT count(*) FROM accounts");
        int bindingsBefore = count("SELECT count(*) FROM google_identity_bindings");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("stranger-token", proof.nonce))
                .thenReturn(new Verified("never-seen-subject", null));
        Reply reply = exportCall(UUID.randomUUID().toString(), proof.id, "stranger-token", null);
        assertEquals(404, reply.status, reply.body);
        assertEquals("not_found", reply.node().path("errorCode").asText());
        assertEquals(accountsBefore, count("SELECT count(*) FROM accounts"));
        assertEquals(bindingsBefore, count("SELECT count(*) FROM google_identity_bindings"));
        assertFalse(challengeConsumed(proof.id));
    }

    @Test
    void bearerAloneWithoutProofIsDenied() {
        BoundAccount account = provision("bearer-only-subject", "bearer-only-signup");
        Reply missing = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\"}",
                Map.of("Authorization", "Bearer " + account.accessToken));
        assertEquals(401, missing.status, missing.body);
        assertEquals("no-store", missing.cacheControl());
        Reply empty = post("/v1/account/export", "{}",
                Map.of("Authorization", "Bearer " + account.accessToken));
        assertEquals(422, empty.status, empty.body);
        assertEquals("validation_error", empty.node().path("errorCode").asText());
    }

    @Test
    void exact300SecondBoundaryRejectsAtExpiryAndConsumesNothing() {
        BoundAccount account = provision("boundary-subject", "boundary-signup");
        ProofChallenge expired = challenge("{\"purpose\":\"export\"}");
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?", java.sql.Timestamp.class,
                UUID.fromString(expired.id)).toInstant();
        proofClock.set(expires);
        lenient().when(verifier.verify("boundary-expired-token", expired.nonce))
                .thenReturn(new Verified("boundary-subject", null));
        Reply rejected = exportCall(account.accountId.toString(), expired.id, "boundary-expired-token", null);
        assertEquals(401, rejected.status, rejected.body);
        assertEquals("invalid_google_credential", rejected.node().path("errorCode").asText());
        assertFalse(challengeConsumed(expired.id));

        ProofChallenge live = challenge("{\"purpose\":\"export\"}");
        Instant liveExpires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?", java.sql.Timestamp.class,
                UUID.fromString(live.id)).toInstant();
        proofClock.set(liveExpires.minusNanos(1));
        lenient().when(verifier.verify("boundary-live-token", live.nonce))
                .thenReturn(new Verified("boundary-subject", null));
        assertEquals(200, exportCall(account.accountId.toString(), live.id, "boundary-live-token", null).status);
        proofClock.set(Instant.now());
    }

    @Test
    void challengeExpiringDuringProviderWaitIsRejectedWithoutConsumption() throws Exception {
        BoundAccount account = provision("slow-provider-subject", "slow-provider-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?", java.sql.Timestamp.class,
                UUID.fromString(proof.id)).toInstant();
        proofClock.set(expires.minus(Duration.ofSeconds(30)));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        lenient().when(verifier.verify("slow-provider-token", proof.nonce)).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(providerRelease.await(60, TimeUnit.SECONDS), "provider wait was not released");
            return new Verified("slow-provider-subject", null);
        });
        Future<Reply> pending;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pending = pool.submit(() -> exportCall(account.accountId.toString(), proof.id, "slow-provider-token", null));
            assertTrue(providerEntered.await(60, TimeUnit.SECONDS), "provider call was not reached");
            // The challenge expires while the provider call is still in flight.
            proofClock.set(expires.plusMillis(1));
            providerRelease.countDown();
            Reply reply = pending.get(60, TimeUnit.SECONDS);
            assertEquals(401, reply.status, reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertFalse(challengeConsumed(proof.id), "expiry during provider wait must not consume the challenge");
        } finally {
            providerRelease.countDown();
            pool.shutdownNow();
            proofClock.set(Instant.now());
        }
    }

    @Test
    void challengeExpiringDuringSubjectAccountLockWaitIsRecheckedAfterRelease() throws Exception {
        BoundAccount account = provision("lock-wait-subject", "lock-wait-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?", java.sql.Timestamp.class,
                UUID.fromString(proof.id)).toInstant();
        proofClock.set(expires.minus(Duration.ofSeconds(30)));
        lenient().when(verifier.verify("lock-wait-token", proof.nonce))
                .thenReturn(new Verified("lock-wait-subject", null));

        // Hold the account row lock on a test transaction so consumption blocks in its lock wait.
        var status = txManager.getTransaction(new DefaultTransactionDefinition());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            jdbc.query("SELECT deleted_at IS NULL FROM accounts WHERE id=? FOR UPDATE",
                    rs -> null, account.accountId);
            int baselineLocks = ungrantedLocks();
            Future<Reply> pending = pool.submit(
                    () -> exportCall(account.accountId.toString(), proof.id, "lock-wait-token", null));
            boolean waited = awaitLockWait(baselineLocks);
            // Expire the challenge exactly at the boundary; the recheck after lock release must reject.
            proofClock.set(expires);
            txManager.commit(status);
            status = null;
            Reply reply = pending.get(60, TimeUnit.SECONDS);
            assertTrue(waited, "export never reached its account lock wait; ungrantedLocks=" + ungrantedLocks()
                    + " reply=" + reply.status + " " + reply.body
                    + " consumed=" + challengeConsumed(proof.id));
            assertEquals(401, reply.status, reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertFalse(challengeConsumed(proof.id), "expiry during lock wait must not consume the challenge");
        } finally {
            if (status != null && !status.isCompleted()) {
                txManager.rollback(status);
            }
            pool.shutdownNow();
            proofClock.set(Instant.now());
        }
    }

    private int ungrantedLocks() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()", Integer.class);
    }

    private boolean awaitLockWait(int baseline) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (ungrantedLocks() > baseline) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    @Test
    void consumedChallengeCannotBeReused() {
        BoundAccount account = provision("reuse-subject", "reuse-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("reuse-token", proof.nonce))
                .thenReturn(new Verified("reuse-subject", null));
        assertEquals(200, exportCall(account.accountId.toString(), proof.id, "reuse-token", null).status);
        Reply replay = exportCall(account.accountId.toString(), proof.id, "reuse-token", null);
        assertEquals(401, replay.status, replay.body);
        assertEquals("invalid_google_credential", replay.node().path("errorCode").asText());
        assertFalse(replay.body.contains("customExercises"));
    }

    @Test
    void nonceMismatchIsRejectedWithoutConsumption() {
        BoundAccount account = provision("nonce-subject", "nonce-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("nonce-token", proof.nonce))
                .thenThrow(new GoogleTokenVerifier.InvalidGoogleToken());
        Reply reply = exportCall(account.accountId.toString(), proof.id, "nonce-token", null);
        assertEquals(401, reply.status, reply.body);
        assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
        assertFalse(challengeConsumed(proof.id));
    }

    @Test
    void concurrentSameChallengeAllowsExactlyOneExport() throws Exception {
        BoundAccount account = provision("race-subject", "race-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("race-token", proof.nonce))
                .thenReturn(new Verified("race-subject", null));
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id + "\",\"idToken\":\"race-token\"}";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> post("/v1/account/export", body, null));
            var second = pool.submit(() -> post("/v1/account/export", body, null));
            Reply one = first.get(60, TimeUnit.SECONDS);
            Reply two = second.get(60, TimeUnit.SECONDS);
            assertEquals(1, List.of(one, two).stream().filter(r -> r.status == 200).count(), one.body + " / " + two.body);
            assertEquals(1, List.of(one, two).stream().filter(r -> r.status == 401).count());
            assertTrue(challengeConsumed(proof.id));
        }
    }

    @Test
    void malformedAndUnknownFieldsAreRejectedWithoutConsumption() {
        BoundAccount account = provision("shape-subject", "shape-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"export\"}");
        assertEquals(400, post("/v1/account/export", "{not-json", null).status);
        Reply duplicate = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"accountId\":\"" + account.accountId
                        + "\",\"challengeId\":\"" + proof.id + "\",\"idToken\":\"x\"}", null);
        assertEquals(400, duplicate.status, duplicate.body);
        Reply unknown = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"x\",\"exportFilter\":\"all\"}", null);
        assertEquals(422, unknown.status, unknown.body);
        assertEquals("validation_error", unknown.node().path("errorCode").asText());
        Reply upper = exportCall(account.accountId.toString().toUpperCase(), proof.id, "x", null);
        assertEquals(422, upper.status, upper.body);
        assertFalse(challengeConsumed(proof.id));
        for (Reply reply : List.of(duplicate, unknown, upper)) {
            assertEquals("no-store", reply.cacheControl());
        }
    }
}
