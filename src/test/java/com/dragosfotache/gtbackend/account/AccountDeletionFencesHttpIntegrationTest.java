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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9b.5 and AC9b.6: live fences, concurrency and new-account separation.
 *
 * <p>All expectations are hand-authored from lifecycle, authentication, retention and
 * service protocol rules. Deterministic gates use short real PostgreSQL lock waits
 * without imposing circular waits. Google verification is mocked at the external
 * boundary; PostgreSQL is isolated Testcontainers with real HTTP.
 *
 * <p>Snapshot and pull assertions distinguish coherent pre-acceptance reads from fresh
 * post-acceptance denial: reuse of an existing cursor token and snapshot continuation
 * create no new stored copy, while new requests after acceptance reject. No late
 * durable copy is created after acceptance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionFencesHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "deletion-fence-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestAccountDeletionHooks deletionHooks;
    @Autowired
    com.dragosfotache.gtbackend.auth.AuthCrypto authCrypto;
    @MockitoBean
    GoogleTokenVerifier verifier;

    enum ExportPauseStage {
        BEFORE_FIRST_BYTE, MID_SEGMENT, BEFORE_FINAL_CLOSURE
    }

    static final class DeterministicExportGate implements AccountExportHooks {
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> admitted =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> midSegment =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> closure =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> release =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        final java.util.concurrent.atomic.AtomicReference<ExportPauseStage> selected =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicBoolean midReached =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean closureReached =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        void arm(ExportPauseStage stage) {
            selected.set(stage);
            admitted.set(new java.util.concurrent.CountDownLatch(1));
            midSegment.set(new java.util.concurrent.CountDownLatch(1));
            closure.set(new java.util.concurrent.CountDownLatch(1));
            release.set(new java.util.concurrent.CountDownLatch(1));
            midReached.set(false);
            closureReached.set(false);
        }

        void disarm() {
            selected.set(null);
            release.get().countDown();
        }

        @Override
        public void beforeFirstByte() {
            if (ExportPauseStage.BEFORE_FIRST_BYTE.equals(selected.get())) {
                admitted.get().countDown();
                awaitOrFail(release, "beforeFirstByte");
            }
        }

        @Override
        public void beforeByteSegment(long ordinal) {
            if (ExportPauseStage.MID_SEGMENT.equals(selected.get()) && ordinal == 5) {
                midReached.set(true);
                midSegment.get().countDown();
                awaitOrFail(release, "midSegment");
            }
        }

        @Override
        public void beforeFinalClosure() {
            if (ExportPauseStage.BEFORE_FINAL_CLOSURE.equals(selected.get())) {
                closureReached.set(true);
                closure.get().countDown();
                awaitOrFail(release, "beforeFinalClosure");
            }
        }

        private void awaitOrFail(
                java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> gate,
                String stage) {
            try {
                if (!gate.get().await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("export gate timeout at " + stage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("export gate interrupted at " + stage, e);
            }
        }
    }

    @TestConfiguration
    static class ExportGateConfiguration {
        @Bean
        @Primary
        DeterministicExportGate deterministicExportGate() {
            return new DeterministicExportGate();
        }
    }

    @Autowired
    DeterministicExportGate exportGate;

    private record BoundAccount(UUID accountId, String subject, String accessToken, String refreshToken) {}
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
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private Reply put(String path, String body, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("HTTP PUT failed: " + path, e);
        }
    }

    private Reply get(String path, String bearer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET();
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("HTTP GET failed: " + path, e);
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
        return new BoundAccount(
                UUID.fromString(grant.node().path("account").path("id").asText()),
                subject,
                grant.node().path("accessToken").asText(),
                grant.node().path("refreshToken").asText());
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
    void ordinaryAccessRefreshAndGrantReplayCannotSurviveAcceptance() {
        BoundAccount account = provision("fence-access-subject", "fence-access-signup");
        BoundAccount other = provision("fence-other-subject", "fence-other-signup");
        // Other account owns one stable workout so preservation is asserted by data, not status alone.
        String otherWorkout = UUID.randomUUID().toString();
        Reply otherCreated = post("/v1/workouts",
                "{\"id\":\"" + otherWorkout + "\",\"name\":\"Other stable\",\"startedAt\":\"2026-01-01T00:00:00Z\"}",
                Map.of("Authorization", "Bearer " + other.accessToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, otherCreated.status, otherCreated.body);
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("fence-access-token", proof.nonce))
                .thenReturn(new Verified("fence-access-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), proof.id, "fence-access-token",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);

        // Ordinary reads, direct mutations and pull reject for the deleted account.
        assertEquals(401, get("/v1/exercises/" + UUID.randomUUID(), account.accessToken).status);
        assertEquals(401, get("/v1/sync/pull", account.accessToken).status);
        Reply refresh = post("/v1/auth/refresh",
                "{\"refreshToken\":\"" + account.refreshToken + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, refresh.status, refresh.body);
        assertEquals("reauthentication_required", refresh.node().path("errorCode").asText());
        // Another account keeps exact 200 behavior and unchanged data.
        Reply otherPull = get("/v1/sync/pull", other.accessToken);
        assertEquals(200, otherPull.status, otherPull.body);
        assertTrue(otherPull.node().path("nextCursor").asText().length() > 1);
        Reply otherRead = get("/v1/workouts/" + otherWorkout, other.accessToken);
        assertEquals(200, otherRead.status, otherRead.body);
        assertEquals(otherWorkout, otherRead.node().path("id").asText());
    }

    @Test
    void preDeletionInFlightSignInIsDeniedWithoutBindingAndAfterBExists() {
        BoundAccount old = provision("inflight-subject", "inflight-a");
        // A sign-in challenge issued before deletion must not become B implicitly,
        // even though its Google proof is still unexpired.
        ProofChallenge staleSignIn = challenge("{}");
        String staleCreated = jdbc.queryForObject("SELECT created_at FROM auth_challenges WHERE id=?",
                java.sql.Timestamp.class, UUID.fromString(staleSignIn.id)).toInstant().toString();
        assertNotNull(staleCreated);

        ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("inflight-delete", deleteProof.nonce))
                .thenReturn(new Verified("inflight-subject", null));
        Reply deleted = deleteCall(old.accountId.toString(), deleteProof.id, "inflight-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);

        // Stale pre-deletion proof denied with no B binding.
        lenient().when(verifier.verify("inflight-stale-token", staleSignIn.nonce))
                .thenReturn(new Verified("inflight-subject", null));
        Reply staleDenied = post("/v1/auth/google",
                "{\"challengeId\":\"" + staleSignIn.id + "\",\"idToken\":\"inflight-stale-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, staleDenied.status, staleDenied.body);

        // A fresh post-deletion challenge creates B.
        ProofChallenge freshSignIn = challenge("{}");
        lenient().when(verifier.verify("inflight-fresh", freshSignIn.nonce))
                .thenReturn(new Verified("inflight-subject", null));
        Reply freshGrant = post("/v1/auth/google",
                "{\"challengeId\":\"" + freshSignIn.id + "\",\"idToken\":\"inflight-fresh\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, freshGrant.status, freshGrant.body);
        assertNotEquals(old.accountId.toString(), freshGrant.node().path("account").path("id").asText());

        // The same stale pre-deletion proof is still denied after B already exists;
        // it must not authorize B through binding replacement.
        Reply staleAfterB = post("/v1/auth/google",
                "{\"challengeId\":\"" + staleSignIn.id + "\",\"idToken\":\"inflight-stale-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, staleAfterB.status, staleAfterB.body);

        // Old credentials, cursors and snapshot tokens from A cannot authorize B.
        assertEquals(401, get("/v1/sync/pull", old.accessToken).status);
    }

    @Test
    void pullInsertionBeforeWinnerKeepsNoLateCopyAndFreshDenies() throws Exception {
        // Explicit before-winner coverage: deletion serializes after its scoped-key gate
        // while the synchronous pull completes first. Deletion-first with read-side
        // pre-insert and held-share gates requires a new production seam; it is requested
        // separately and is not claimed here. Do not hold SHARE while awaiting the writer.
        BoundAccount account = provision("pull-overlap-subject", "pull-overlap-signup");
        int cursorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("pull-overlap-token", proof.nonce))
                .thenReturn(new Verified("pull-overlap-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                + "\",\"idToken\":\"pull-overlap-token\",\"confirm\":true}";
        deletionHooks.armScopedKeyGate();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitScopedKeyEntered(), "deletion never reached scoped-key gate");
            // While deletion holds its scoped-key serialization, a fresh pull requiring a new
            // cursor insert overlaps; it must not create a late durable copy.
            Reply overlapped = get("/v1/sync/pull", account.accessToken);
            deletionHooks.releaseScopedKey();
            Reply accepted = deletion.get(60, TimeUnit.SECONDS);
            assertEquals(202, accepted.status, accepted.body);
            // The overlapped pull either observed the live view before acceptance or was
            // denied after acceptance; in either case no late copy is created after commit.
            assertTrue(overlapped.status == 200 || overlapped.status == 401, overlapped.body);
            int cursorsAfter = jdbc.queryForObject(
                    "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class,
                    account.accountId);
            assertTrue(cursorsAfter == cursorsBefore || cursorsAfter == cursorsBefore + 1,
                    "cursors before=" + cursorsBefore + " after=" + cursorsAfter);
            // A fresh pull after acceptance is always denied.
            assertEquals(401, get("/v1/sync/pull", account.accessToken).status);
        } finally {
            deletionHooks.releaseScopedKey();
            pool.shutdownNow();
        }
    }

    @Test
    void genuinelyOverlappingSignInIsDeniedWhenDeletionCommitsDuringProviderWait() throws Exception {
        BoundAccount old = provision("overlap-subject", "overlap-a");
        ProofChallenge staleSignIn = challenge("{}");
        java.util.concurrent.CountDownLatch providerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch providerRelease = new java.util.concurrent.CountDownLatch(1);
        lenient().when(verifier.verify("overlap-stale-token", staleSignIn.nonce)).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(providerRelease.await(60, TimeUnit.SECONDS), "provider wait was not released");
            return new Verified("overlap-subject", null);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> pending = pool.submit(() -> post("/v1/auth/google",
                    "{\"challengeId\":\"" + staleSignIn.id + "\",\"idToken\":\"overlap-stale-token\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString())));
            assertTrue(providerEntered.await(60, TimeUnit.SECONDS), "exchange never reached provider");
            ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("overlap-delete", deleteProof.nonce))
                    .thenReturn(new Verified("overlap-subject", null));
            Reply deleted = deleteCall(old.accountId.toString(), deleteProof.id, "overlap-delete",
                    UUID.randomUUID().toString());
            assertEquals(202, deleted.status, deleted.body);
            providerRelease.countDown();
            Reply overlapped = pending.get(60, TimeUnit.SECONDS);
            assertEquals(401, overlapped.status, overlapped.body);
            assertEquals("invalid_google_credential", overlapped.node().path("errorCode").asText());
            // No B was created by the overlapping stale proof.
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM google_identity_bindings WHERE google_subject=?",
                    Integer.class, "overlap-subject"));
        } finally {
            providerRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotAndPullDistinguishPreAcceptanceReadsFromPostAcceptanceDenial() {
        BoundAccount account = provision("snapshot-fence-subject", "snapshot-fence-signup");
        // Establish a cursor token and snapshot baseline before acceptance.
        Reply pullBefore = get("/v1/sync/pull", account.accessToken);
        assertEquals(200, pullBefore.status, pullBefore.body);
        String cursorBefore = pullBefore.node().path("nextCursor").asText();
        assertFalse(cursorBefore.isEmpty());
        int cursorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);
        assertTrue(cursorsBefore >= 1);

        Reply snapshotStart = get("/v1/sync/snapshot?limit=200", account.accessToken);
        assertEquals(200, snapshotStart.status, snapshotStart.body);
        assertTrue(snapshotStart.node().path("baselineCursor").asText().length() > 1);
        int snapshotsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId);
        assertTrue(snapshotsBefore >= 1);

        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("snapshot-fence-token", proof.nonce))
                .thenReturn(new Verified("snapshot-fence-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), proof.id, "snapshot-fence-token",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);

        // Fresh snapshot and pull requests after acceptance reject with safe 401.
        assertEquals(401, get("/v1/sync/snapshot?limit=200", account.accessToken).status);
        assertEquals(401, get("/v1/sync/pull", account.accessToken).status);
        // Continuation with the pre-acceptance cursor is denied and creates no new copy.
        Reply pullAfter = get("/v1/sync/pull?cursor=" + cursorBefore, account.accessToken);
        assertEquals(401, pullAfter.status, pullAfter.body);
        assertEquals("authentication_required", pullAfter.node().path("errorCode").asText());
        // No late durable copy was created after acceptance.
        assertEquals(snapshotsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId));
        assertEquals(cursorsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId));
    }

    @Test
    void deleteFirstBlocksDirectWriterAndDeniesPostAcceptanceWrites() throws Exception {
        BoundAccount account = provision("race-direct-subject", "race-direct-signup");
        deletionHooks.armFinalChecksGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String key = UUID.randomUUID().toString();
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("race-direct-delete", proof.nonce))
                    .thenReturn(new Verified("race-direct-subject", null));
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"race-direct-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitFinalChecksEntered(), "deletion never reached final-checks gate");
            String workout = UUID.randomUUID().toString();
            int baselineLocks = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()", Integer.class);
            Future<Reply> writer = pool.submit(() -> post("/v1/workouts",
                    "{\"id\":\"" + workout + "\",\"name\":\"Race direct\",\"startedAt\":\"2026-01-01T00:00:00Z\"}",
                    Map.of("Authorization", "Bearer " + account.accessToken,
                            "Idempotency-Key", UUID.randomUUID().toString())));
            assertTrue(awaitUngrantedIncrease(baselineLocks), "direct writer never blocked on held deletion lock");
            assertFalse(writer.isDone(), "direct writer must block on held deletion lock");
            assertFalse(deletion.isDone(), "deletion must hold its lock");
            deletionHooks.releaseFinalChecks();
            Reply accepted = deletion.get(60, TimeUnit.SECONDS);
            assertEquals(202, accepted.status, accepted.body);
            Reply writerResult = writer.get(60, TimeUnit.SECONDS);
            assertEquals(401, writerResult.status, writerResult.body);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE id=?",
                    Integer.class, UUID.fromString(workout)));
            assertEquals(401, get("/v1/workouts/" + workout, account.accessToken).status);
        } finally {
            deletionHooks.releaseFinalChecks();
            pool.shutdownNow();
        }
    }

    /**
     * Committed-before supplement, not an overlapping winner-order race: a direct write
     * commits before deletion, remains committed, and later writes deny.
     */
    @Test
    void directWriterFirstCommitsThenDeletionDeniesLaterWritesAsSupplement() {
        BoundAccount account = provision("race-direct-first-subject", "race-direct-first-signup");
        BoundAccount other = provision("race-direct-other-subject", "race-direct-other-signup");
        String workout = UUID.randomUUID().toString();
        Reply created = post("/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Writer first\",\"startedAt\":\"2026-01-01T00:00:00Z\"}",
                Map.of("Authorization", "Bearer " + account.accessToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, created.status, created.body);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE id=? AND deleted_at IS NULL",
                Integer.class, UUID.fromString(workout)));
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("race-direct-first-delete", proof.nonce))
                .thenReturn(new Verified("race-direct-first-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), proof.id, "race-direct-first-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE id=?",
                Integer.class, UUID.fromString(workout)));
        assertEquals(401, get("/v1/workouts/" + workout, account.accessToken).status);
        Reply otherPull = get("/v1/sync/pull", other.accessToken);
        assertEquals(200, otherPull.status, otherPull.body);
    }

    private Integer awaitReplayBlockedOnDelete(int deletePid, UUID originalRefreshKey) {
        String canonicalKey = originalRefreshKey.toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            java.util.List<Integer> waiting = jdbc.query(
                    "WITH wanted AS (SELECT hashtextextended('refresh:' || ?::text, 0) AS key) "
                            + "SELECT blocked.pid FROM pg_locks blocked, wanted WHERE NOT blocked.granted "
                            + "AND pg_blocking_pids(blocked.pid) @> ARRAY[?] "
                            + "AND EXISTS (SELECT 1 FROM pg_locks granted, wanted "
                            + "WHERE granted.pid=blocked.pid AND granted.locktype='advisory' AND granted.granted "
                            + "AND granted.objsubid=1 "
                            + "AND granted.classid::bigint = ((wanted.key >> 32) & 4294967295) "
                            + "AND granted.objid::bigint = (wanted.key & 4294967295)) LIMIT 1",
                    (rs, row) -> rs.getInt(1), canonicalKey, deletePid);
            if (!waiting.isEmpty()) {
                return waiting.get(0);
            }
            Thread.onSpinWait();
        }
        System.out.println("REPLAY_WAIT_DIAGNOSTIC deletePid=" + deletePid + " refreshKey=" + canonicalKey);
        try {
            Long wantedKey = jdbc.queryForObject(
                    "SELECT hashtextextended('refresh:' || ?::text, 0)", Long.class, canonicalKey);
            System.out.println("REPLAY_WAIT_DIAGNOSTIC wantedKey=" + wantedKey
                    + " high=" + ((wantedKey >> 32) & 4294967295L)
                    + " low=" + (wantedKey & 4294967295L));
        } catch (Exception diagnosticFault) {
            System.out.println("REPLAY_WAIT_DIAGNOSTIC wantedKey unavailable: " + diagnosticFault);
        }
        for (java.util.Map<String, Object> row : jdbc.queryForList(
                "SELECT pid, locktype, mode, granted, classid, objid, objsubid FROM pg_locks "
                        + "WHERE locktype='advisory' ORDER BY pid")) {
            System.out.println("REPLAY_WAIT_DIAGNOSTIC advisory=" + row);
        }
        for (java.util.Map<String, Object> row : jdbc.queryForList(
                "SELECT pid, locktype, mode, granted, pg_blocking_pids(pid) AS blocking FROM pg_locks "
                        + "WHERE NOT granted ORDER BY pid LIMIT 20")) {
            System.out.println("REPLAY_WAIT_DIAGNOSTIC waiting=" + row);
        }
        try {
            java.util.List<java.util.Map<String, Object>> holders = jdbc.queryForList(
                    "WITH wanted AS (SELECT hashtextextended('refresh:' || ?::text, 0) AS key) "
                            + "SELECT pid, granted FROM pg_locks, wanted WHERE locktype='advisory' "
                            + "AND objsubid=1 AND classid::bigint = ((wanted.key >> 32) & 4294967295) "
                            + "AND objid::bigint = (wanted.key & 4294967295) ORDER BY pid",
                    canonicalKey);
            System.out.println("REPLAY_WAIT_DIAGNOSTIC exactKeyHolders=" + holders.size() + " " + holders);
        } catch (Exception diagnosticFault) {
            System.out.println("REPLAY_WAIT_DIAGNOSTIC exactKeyHolders unavailable: " + diagnosticFault);
        }
        return null;
    }

    private boolean awaitUngrantedIncrease(int baseline) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()", Integer.class);
            if (waiting > baseline) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /**
     * Committed-before supplement, not an overlapping winner-order race: a sync mutation
     * commits before deletion, remains committed, and later pushes deny.
     */
    @Test
    void syncWriterFirstCommitsThenDeletionDeniesLaterPushAsSupplement() {
        BoundAccount account = provision("race-sync-first-subject", "race-sync-first-signup");
        String routine = UUID.randomUUID().toString();
        String mutationId = UUID.randomUUID().toString();
        String mutation = "{\"mutationId\":\"" + mutationId + "\",\"type\":\"routine.upsert\","
                + "\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,"
                + "\"payload\":{\"id\":\"" + routine + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}}";
        Reply pushed = post("/v1/sync/push", "{\"mutations\":[" + mutation + "]}",
                Map.of("Authorization", "Bearer " + account.accessToken));
        assertEquals(200, pushed.status, pushed.body);
        assertTrue(pushed.body.contains("accepted"), pushed.body);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM routines WHERE id=? AND deleted_at IS NULL",
                Integer.class, UUID.fromString(routine)));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM processed_mutations WHERE owner_id=?", Integer.class, account.accountId));
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("race-sync-first-delete", proof.nonce))
                .thenReturn(new Verified("race-sync-first-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), proof.id, "race-sync-first-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM routines WHERE id=?",
                Integer.class, UUID.fromString(routine)));
        Reply denied = post("/v1/sync/push", "{\"mutations\":[" + mutation.replace(mutationId, UUID.randomUUID().toString()) + "]}",
                Map.of("Authorization", "Bearer " + account.accessToken));
        assertEquals(401, denied.status, denied.body);
    }

    @Test
    void deleteHoldsGateWhileAcceptedReplayWaitsThenDeniesWithoutRenewal() throws Exception {
        BoundAccount account = provision("race-sync-subject", "race-sync-signup");
        // Preserve an accepted refresh outcome before deletion: same key and exact body
        // replays the original grant while the session is live. The rotation returns a
        // genuinely current refresh token used for the live race below.
        UUID refreshKey = UUID.randomUUID();
        String refreshBody = "{\"refreshToken\":\"" + account.refreshToken + "\"}";
        Reply eligible = post("/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey.toString()));
        assertEquals(200, eligible.status, eligible.body);
        String currentRefresh = eligible.node().path("refreshToken").asText();
        assertNotNull(currentRefresh);
        Reply eligibleReplay = post("/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey.toString()));
        assertEquals(200, eligibleReplay.status, eligibleReplay.body);
        assertEquals(eligible.node().path("refreshToken").asText(), eligibleReplay.node().path("refreshToken").asText());
        deletionHooks.armFinalChecksGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String key = UUID.randomUUID().toString();
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("race-sync-delete", proof.nonce))
                    .thenReturn(new Verified("race-sync-subject", null));
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"race-sync-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitFinalChecksEntered(), "deletion never reached final-checks gate");
            Future<Reply> overlappingReplay = pool.submit(() -> post("/v1/auth/refresh", refreshBody,
                    Map.of("Idempotency-Key", refreshKey.toString())));
            Integer deletePid = deletionHooks.markerPid("finalChecks");
            assertNotNull(deletePid, "deletion backend PID was not captured at final-checks gate");
            Integer replayPid = awaitReplayBlockedOnDelete(deletePid, refreshKey);
            assertNotNull(replayPid, "accepted replay never waited on the deleting backend");
            assertFalse(overlappingReplay.isDone(), "accepted replay must reach the held-lock wait");
            assertFalse(deletion.isDone(), "deletion must hold its lock");
            deletionHooks.releaseFinalChecks();
            assertEquals(202, deletion.get(60, TimeUnit.SECONDS).status);
            Reply overlappingResult = overlappingReplay.get(60, TimeUnit.SECONDS);
            assertEquals(401, overlappingResult.status, overlappingResult.body);
            assertEquals("reauthentication_required", overlappingResult.node().path("errorCode").asText());
            String routine = UUID.randomUUID().toString();
            String mutationId = UUID.randomUUID().toString();
            String mutation = "{\"mutationId\":\"" + mutationId + "\",\"type\":\"routine.upsert\","
                    + "\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,"
                    + "\"payload\":{\"id\":\"" + routine + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}}";
            Reply syncDenied = post("/v1/sync/push", "{\"mutations\":[" + mutation + "]}",
                    Map.of("Authorization", "Bearer " + account.accessToken));
            assertEquals(401, syncDenied.status, syncDenied.body);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM routines WHERE id=?",
                    Integer.class, UUID.fromString(routine)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM processed_mutations WHERE owner_id=? AND mutation_id=?::uuid",
                    Integer.class, account.accountId, UUID.fromString(mutationId)));
            String raceRefreshBody = "{\"refreshToken\":\"" + currentRefresh + "\"}";
            Reply freshDenied = post("/v1/auth/refresh", raceRefreshBody,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(401, freshDenied.status, freshDenied.body);
            Reply replay = post("/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey.toString()));
            assertEquals(401, replay.status, replay.body);
            assertEquals("reauthentication_required", replay.node().path("errorCode").asText());
        } finally {
            deletionHooks.releaseFinalChecks();
            pool.shutdownNow();
        }
    }

    @Test
    void deleteHoldsGateWhileValidSyncMutationWaitsThenDeniesWithoutSideEffects() throws Exception {
        BoundAccount account = provision("split-sync-subject", "split-sync-signup");
        BoundAccount other = provision("split-sync-other-subject", "split-sync-other-signup");
        Map<String, Object> syncStateBefore = jdbc.queryForMap(
                "SELECT committed_cursor, pruned_through FROM account_sync_state WHERE account_id=?",
                account.accountId);
        int rootReservationsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM root_id_reservations WHERE owner_id=?", Integer.class, account.accountId);
        int childReservationsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM child_id_reservations WHERE owner_id=?", Integer.class, account.accountId);
        int cursorTokensBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);
        deletionHooks.armFinalChecksGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String key = UUID.randomUUID().toString();
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("split-sync-delete", proof.nonce))
                    .thenReturn(new Verified("split-sync-subject", null));
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"split-sync-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitFinalChecksEntered(), "deletion never reached final-checks gate");
            Integer deletePid = deletionHooks.markerPid("finalChecks");
            assertNotNull(deletePid, "deletion backend PID was not captured");
            String routine = UUID.randomUUID().toString();
            String mutationId = UUID.randomUUID().toString();
            String mutation = "{\"mutationId\":\"" + mutationId + "\",\"type\":\"routine.upsert\","
                    + "\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,"
                    + "\"payload\":{\"id\":\"" + routine + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}}";
            Future<Reply> contender = pool.submit(() -> post("/v1/sync/push",
                    "{\"mutations\":[" + mutation + "]}",
                    Map.of("Authorization", "Bearer " + account.accessToken)));
            Integer waiterPid = awaitBlockedOnDelete(deletePid);
            assertNotNull(waiterPid, "sync mutation never waited on the deleting backend");
            assertFalse(contender.isDone(), "sync contender must reach the held-lock wait");
            assertFalse(deletion.isDone(), "deletion must hold its lock");
            deletionHooks.releaseFinalChecks();
            assertEquals(202, deletion.get(60, TimeUnit.SECONDS).status);
            Reply denied = contender.get(60, TimeUnit.SECONDS);
            assertEquals(401, denied.status, denied.body);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM routines WHERE id=?",
                    Integer.class, UUID.fromString(routine)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM processed_mutations WHERE owner_id=? AND mutation_id=?::uuid",
                    Integer.class, account.accountId, UUID.fromString(mutationId)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM sync_change_batches WHERE account_id=?", Integer.class, account.accountId));
            assertEquals(syncStateBefore.get("committed_cursor"), jdbc.queryForObject(
                    "SELECT committed_cursor FROM account_sync_state WHERE account_id=?", Long.class,
                    account.accountId));
            assertEquals(syncStateBefore.get("pruned_through"), jdbc.queryForObject(
                    "SELECT pruned_through FROM account_sync_state WHERE account_id=?", Long.class,
                    account.accountId));
            assertEquals(rootReservationsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM root_id_reservations WHERE owner_id=?", Integer.class, account.accountId));
            assertEquals(childReservationsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM child_id_reservations WHERE owner_id=?", Integer.class, account.accountId));
            assertEquals(cursorTokensBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId));
            Reply otherPull = get("/v1/sync/pull", other.accessToken);
            assertEquals(200, otherPull.status, otherPull.body);
        } finally {
            deletionHooks.releaseFinalChecks();
            pool.shutdownNow();
        }
    }

    @Test
    void deleteHoldsGateWhileEligibleFreshRefreshWaitsThenDeniesWithoutRenewal() throws Exception {
        BoundAccount first = provision("split-refresh-subject", "split-refresh-signup");
        BoundAccount second = provision("split-refresh-subject", "split-refresh-second-signup");
        assertEquals(first.accountId, second.accountId);
        String testedRefresh = second.refreshToken;
        byte[] fingerprint = authCrypto.digest(testedRefresh);
        Map<String, Object> credential = jdbc.queryForMap(
                "SELECT consumed_at, expires_at FROM auth_credentials WHERE fingerprint=?", (Object) fingerprint);
        assertNull(credential.get("consumed_at"), "tested refresh must be unconsumed before deletion");
        java.sql.Timestamp expiresBefore =
                (java.sql.Timestamp) credential.get("expires_at");
        assertTrue(expiresBefore.toInstant().isAfter(java.time.Instant.now()),
                "tested refresh must be unexpired before deletion");
        int credentialsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_credentials WHERE session_id IN "
                        + "(SELECT id FROM auth_sessions WHERE account_id=?)", Integer.class, first.accountId);
        deletionHooks.armFinalChecksGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String key = UUID.randomUUID().toString();
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("split-refresh-delete", proof.nonce))
                    .thenReturn(new Verified("split-refresh-subject", null));
            String body = "{\"accountId\":\"" + first.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"split-refresh-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            assertTrue(deletionHooks.awaitFinalChecksEntered(), "deletion never reached final-checks gate");
            Integer deletePid = deletionHooks.markerPid("finalChecks");
            assertNotNull(deletePid, "deletion backend PID was not captured");
            UUID freshKey = UUID.randomUUID();
            String freshBody = "{\"refreshToken\":\"" + testedRefresh + "\"}";
            Future<Reply> contender = pool.submit(() -> post("/v1/auth/refresh", freshBody,
                    Map.of("Idempotency-Key", freshKey.toString())));
            Integer waiterPid = awaitReplayBlockedOnDelete(deletePid, freshKey);
            assertNotNull(waiterPid, "fresh refresh never waited on the deleting backend");
            assertFalse(contender.isDone(), "fresh refresh must reach the held-lock wait");
            assertFalse(deletion.isDone(), "deletion must hold its lock");
            deletionHooks.releaseFinalChecks();
            assertEquals(202, deletion.get(60, TimeUnit.SECONDS).status);
            Reply denied = contender.get(60, TimeUnit.SECONDS);
            assertEquals(401, denied.status, denied.body);
            assertEquals("reauthentication_required", denied.node().path("errorCode").asText());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_idempotency WHERE operation='refresh' AND request_key=?",
                    Integer.class, freshKey));
            Map<String, Object> after = jdbc.queryForMap(
                    "SELECT consumed_at, expires_at FROM auth_credentials WHERE fingerprint=?", (Object) fingerprint);
            assertNull(after.get("consumed_at"), "denied contender must not consume the tested refresh");
            assertEquals(expiresBefore, (java.sql.Timestamp) after.get("expires_at"));
            assertEquals(credentialsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_credentials WHERE session_id IN "
                            + "(SELECT id FROM auth_sessions WHERE account_id=?)", Integer.class, first.accountId));
        } finally {
            deletionHooks.releaseFinalChecks();
            pool.shutdownNow();
        }
    }

    private Integer awaitBlockedOnDelete(int deletePid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            java.util.List<Integer> waiting = jdbc.query(
                    "SELECT pid FROM pg_locks WHERE NOT granted "
                            + "AND pg_blocking_pids(pid) @> ARRAY[?] LIMIT 1",
                    (rs, row) -> rs.getInt(1), deletePid);
            if (!waiting.isEmpty()) {
                return waiting.get(0);
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private void createLargeLiveState(BoundAccount account, int customs) {
        for (int i = 0; i < customs; i++) {
            String custom = UUID.randomUUID().toString();
            Reply created = post("/v1/exercises",
                    "{\"id\":\"" + custom + "\",\"name\":\"Large live custom number " + i
                            + " with padding to exceed segment size 0123456789\"}",
                    Map.of("Authorization", "Bearer " + account.accessToken,
                            "Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(201, created.status, created.body);
        }
    }

    @Test
    void admittedBeforeFirstByteCancellationAbortsWithoutDataRelease() throws Exception {
        BoundAccount account = provision("export-admitted-subject", "export-admitted-signup");
        createLargeLiveState(account, 20);
        ProofChallenge exportProof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("export-admitted-token", exportProof.nonce))
                .thenReturn(new Verified("export-admitted-subject", null));
        String exportBody = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                + exportProof.id + "\",\"idToken\":\"export-admitted-token\"}";
        exportGate.arm(ExportPauseStage.BEFORE_FIRST_BYTE);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        InputStream[] streamHolder = new InputStream[1];
        try {
            Future<HttpResponse<InputStream>> pending = pool.submit(() -> {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/v1/account/export"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(exportBody, StandardCharsets.UTF_8))
                        .build();
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            });
            assertTrue(exportGate.admitted.get().await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "export never reached admitted before-first-byte stage");
            ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("export-admitted-delete", deleteProof.nonce))
                    .thenReturn(new Verified("export-admitted-subject", null));
            Reply deleted = deleteCall(account.accountId.toString(), deleteProof.id,
                    "export-admitted-delete", UUID.randomUUID().toString());
            assertEquals(202, deleted.status, deleted.body);
            exportGate.release.get().countDown();
            HttpResponse<InputStream> response = pending.get(60, TimeUnit.SECONDS);
            streamHolder[0] = response.body();
            RetainedBytes retained = readRetainedResult(streamHolder[0]);
            String body = new String(retained.bytes(), StandardCharsets.UTF_8);
            // Pre-byte cancellation carries a sanitized 404/no-store error envelope; it must
            // release no export data while consuming the admitted proof.
            assertEquals(404, response.statusCode(), "admitted cancellation body: " + body);
            assertTrue(body.contains("not_found"), "pre-byte abort body: " + body);
            assertFalse(body.contains("\"exportVersion\":1"), "no export data may be released");
            assertTrue(challengeConsumed(exportProof.id), "admitted export consumes its proof");
            Reply oldProof = post("/v1/account/export", exportBody, null);
            assertEquals(401, oldProof.status, oldProof.body);
        } finally {
            exportGate.disarm();
            if (streamHolder[0] != null) {
                try {
                    streamHolder[0].close();
                } catch (Exception ignored) {
                }
            }
            pool.shutdownNow();
        }
    }

    @Test
    void exportBeforeFirstByteIsPreventedWhenDeletionWinsFirst() throws Exception {
        BoundAccount account = provision("export-fence-subject", "export-fence-signup");
        createLargeLiveState(account, 20);
        ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("export-fence-delete", deleteProof.nonce))
                .thenReturn(new Verified("export-fence-subject", null));
        Reply deleted = deleteCall(account.accountId.toString(), deleteProof.id, "export-fence-delete",
                UUID.randomUUID().toString());
        assertEquals(202, deleted.status, deleted.body);

        ProofChallenge exportProof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("export-fence-token", exportProof.nonce))
                .thenReturn(new Verified("export-fence-subject", null));
        InputStream stream = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/v1/account/export"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                                    + exportProof.id + "\",\"idToken\":\"export-fence-token\"}",
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(404, response.statusCode());
            stream = response.body();
            byte[] bytes = readRetained(stream);
            String body = new String(bytes, StandardCharsets.UTF_8);
            assertFalse(body.contains("\"exportVersion\":1"), "deleted export must not release data");
            assertFalse(challengeConsumed(exportProof.id),
                    "pre-admission denial must not consume the rejected proof");
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Test
    void midStreamDeletionInsideOneHugeLiveRootAbortsWithoutPartialSuccess() throws Exception {
        BoundAccount account = provision("export-midstream-subject", "export-midstream-signup");
        createHugeLiveRoutine(account);
        ProofChallenge exportProof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("export-midstream-token", exportProof.nonce))
                .thenReturn(new Verified("export-midstream-subject", null));
        String exportBody = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                + exportProof.id + "\",\"idToken\":\"export-midstream-token\"}";
        exportGate.arm(ExportPauseStage.MID_SEGMENT);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        InputStream[] streamHolder = new InputStream[1];
        try {
            Future<HttpResponse<InputStream>> pending = pool.submit(() -> {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/v1/account/export"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(exportBody, StandardCharsets.UTF_8))
                        .build();
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            });
            assertTrue(exportGate.midSegment.get().await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "export never reached mid-segment inside one huge live root");
            assertTrue(exportGate.midReached.get(), "mid-segment stage was not observed");
            // Header plus byte probe BEFORE deletion proves released outward data: the
            // background send has committed 200 with attachment headers once the servlet
            // buffer flushed past the later segment.
            HttpResponse<InputStream> earlyProbe = null;
            try {
                earlyProbe = pending.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException notYetCommitted) {
                fail("export headers were never committed before deletion; no emitted data observed");
            }
            streamHolder[0] = earlyProbe.body();
            assertEquals(200, earlyProbe.statusCode());
            byte[] prefix = readPositivePrefix(streamHolder[0]);
            assertTrue(prefix.length > 0, "no emitted byte observed before deletion");
            ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("export-midstream-delete", deleteProof.nonce))
                    .thenReturn(new Verified("export-midstream-subject", null));
            Reply deleted = deleteCall(account.accountId.toString(), deleteProof.id,
                    "export-midstream-delete", UUID.randomUUID().toString());
            assertEquals(202, deleted.status, deleted.body);
            exportGate.release.get().countDown();
            HttpResponse<InputStream> response = pending.get(60, TimeUnit.SECONDS);
            RetainedBytes retained = readRetainedResult(streamHolder[0]);
            retained = new RetainedBytes(concatPrefix(prefix, retained.bytes()), retained.failure());
            byte[] bytes = retained.bytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            // Server-side gate and consumption prove the stream had started inside one huge
            // live root; header commitment plus retained partial bytes prove emitted data.
            // Completeness is parsed from real bytes with EOF and required fields.
            assertTrue(exportGate.midReached.get(), "mid-segment stage was not reached");
            assertTrue(body.contains("\"customExercises\"") || body.contains("Huge live routine"),
                    "retained bytes lack outward export data: " + body.length() + " bytes");
            boolean completeFile = isCompleteExport(response.statusCode(), body);
            assertFalse(completeFile, "mid-stream deletion must not complete partial JSON as success: "
                    + response.statusCode() + " bytes=" + bytes.length);
            assertFalse(body.contains("errorCode"), "post-byte abort must not append an error envelope");
            assertTrue(challengeConsumed(exportProof.id), "admitted export consumes its proof");
            Reply oldProof = post("/v1/account/export", exportBody, null);
            assertEquals(401, oldProof.status, oldProof.body);
        } finally {
            exportGate.disarm();
            if (streamHolder[0] != null) {
                try {
                    streamHolder[0].close();
                } catch (Exception ignored) {
                }
            }
            pool.shutdownNow();
        }
    }

    @Test
    void finalClosureDeletionAbortsExportWithoutSuccessFlush() throws Exception {
        BoundAccount account = provision("export-closure-subject", "export-closure-signup");
        createHugeLiveRoutine(account);
        ProofChallenge exportProof = challenge("{\"purpose\":\"export\"}");
        lenient().when(verifier.verify("export-closure-token", exportProof.nonce))
                .thenReturn(new Verified("export-closure-subject", null));
        String exportBody = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                + exportProof.id + "\",\"idToken\":\"export-closure-token\"}";
        exportGate.arm(ExportPauseStage.BEFORE_FINAL_CLOSURE);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        InputStream[] streamHolder = new InputStream[1];
        try {
            Future<HttpResponse<InputStream>> pending = pool.submit(() -> {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/v1/account/export"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(exportBody, StandardCharsets.UTF_8))
                        .build();
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            });
            assertTrue(exportGate.closure.get().await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "export never reached before-final-closure stage");
            assertTrue(exportGate.closureReached.get(), "closure stage was not observed");
            HttpResponse<InputStream> earlyClosure = null;
            try {
                earlyClosure = pending.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException notYetCommitted) {
                fail("closure headers were never committed before deletion; no emitted data observed");
            }
            streamHolder[0] = earlyClosure.body();
            assertEquals(200, earlyClosure.statusCode());
            byte[] closurePrefix = readPositivePrefix(streamHolder[0]);
            assertTrue(closurePrefix.length > 0, "no emitted byte observed before closure deletion");
            ProofChallenge deleteProof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("export-closure-delete", deleteProof.nonce))
                    .thenReturn(new Verified("export-closure-subject", null));
            Reply deleted = deleteCall(account.accountId.toString(), deleteProof.id,
                    "export-closure-delete", UUID.randomUUID().toString());
            assertEquals(202, deleted.status, deleted.body);
            exportGate.release.get().countDown();
            HttpResponse<InputStream> response = pending.get(60, TimeUnit.SECONDS);
            RetainedBytes retained = readRetainedResult(streamHolder[0]);
            retained = new RetainedBytes(concatPrefix(closurePrefix, retained.bytes()), retained.failure());
            byte[] bytes = retained.bytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(exportGate.closureReached.get(), "closure stage was not reached");
            assertTrue(body.contains("\"customExercises\"") || body.contains("Huge live routine"),
                    "retained closure bytes lack outward export data: " + body.length() + " bytes");
            boolean completeFile = isCompleteExport(response.statusCode(), body);
            assertFalse(completeFile, "final-closure deletion must not flush success");
            assertFalse(body.contains("errorCode"), "closure abort must not append an error envelope");
            assertTrue(challengeConsumed(exportProof.id), "admitted export consumes its proof");
        } finally {
            exportGate.disarm();
            if (streamHolder[0] != null) {
                try {
                    streamHolder[0].close();
                } catch (Exception ignored) {
                }
            }
            pool.shutdownNow();
        }
    }

    private static boolean isCompleteExport(int status, String body) {
        if (status != 200 || body.contains("errorCode")) {
            return false;
        }
        com.fasterxml.jackson.core.JsonFactory factory = new com.fasterxml.jackson.core.JsonFactory();
        factory.configure(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        try (com.fasterxml.jackson.core.JsonParser parser = factory.createParser(body)) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject()) {
                return false;
            }
            if (parser.nextToken() != null) {
                return false;
            }
            if (!root.path("exportVersion").isInt() || root.path("exportVersion").intValue() != 1
                    || !root.path("accountId").isTextual() || !root.path("exportedAt").isTextual()
                    || !root.path("catalogueVersion").isTextual()
                    || !root.path("customExercises").isArray() || !root.path("routines").isArray()
                    || !root.path("workouts").isArray()) {
                return false;
            }
            return true;
        } catch (Exception notComplete) {
            return false;
        }
    }

    @org.junit.jupiter.api.Test
    void exportCompletenessHelperDistinguishesFullTruncatedTrailingAndDuplicate() {
        String account = UUID.randomUUID().toString();
        String full = "{\"exportVersion\":1,\"accountId\":\"" + account + "\",\"exportedAt\":"
                + "\"2026-01-01T00:00:00Z\",\"catalogueVersion\":\"gymtracka-catalogue-v1\","
                + "\"customExercises\":[],\"routines\":[],\"workouts\":[]}";
        assertTrue(isCompleteExport(200, full));
        assertFalse(isCompleteExport(200, full.substring(0, full.length() / 2)));
        assertFalse(isCompleteExport(200, full + "trailing"));
        assertFalse(isCompleteExport(200, full.replace("\"routines\":[],", "\"routines\":[],\"routines\":[],")));
        assertFalse(isCompleteExport(404, full));
        assertFalse(isCompleteExport(200, full.replace("exportVersion", "errorCode")));
    }

    private static byte[] readPositivePrefix(InputStream stream) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int available = stream.available();
            if (available > 0) {
                byte[] prefix = new byte[Math.min(available, 8192)];
                int read = stream.read(prefix);
                if (read > 0) {
                    byte[] exact = new byte[read];
                    System.arraycopy(prefix, 0, exact, 0, read);
                    return exact;
                }
            }
            Thread.onSpinWait();
        }
        fail("no emitted byte observed before deletion within bounded wait");
        return new byte[0];
    }

    private static byte[] concatPrefix(byte[] prefix, byte[] rest) {
        byte[] out = new byte[prefix.length + rest.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(rest, 0, out, prefix.length, rest.length);
        return out;
    }

    private void createHugeLiveRoutine(BoundAccount account) {
        String custom = UUID.randomUUID().toString();
        Reply customCreated = post("/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Huge root custom\"}",
                Map.of("Authorization", "Bearer " + account.accessToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, customCreated.status, customCreated.body);
        StringBuilder exercises = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                exercises.append(",");
            }
            exercises.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"exerciseId\":\"")
                    .append(custom).append("\",\"position\":").append(i)
                    .append(",\"restDurationSeconds\":30,\"sets\":[");
            for (int s = 0; s < 10; s++) {
                if (s > 0) {
                    exercises.append(",");
                }
                exercises.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"position\":")
                        .append(s).append(",\"repetitions\":8,\"weightKg\":60.0}");
            }
            exercises.append("]}");
        }
        exercises.append("]");
        String routine = UUID.randomUUID().toString();
        assertEquals(201, post("/v1/routines", "{\"id\":\"" + routine + "\"}",
                Map.of("Authorization", "Bearer " + account.accessToken,
                        "Idempotency-Key", UUID.randomUUID().toString())).status);
        Reply updated = put("/v1/routines/" + routine,
                "{\"id\":\"" + routine + "\",\"name\":\"Huge live routine\",\"isDraft\":true,"
                        + "\"exercises\":" + exercises + "}",
                Map.of("Authorization", "Bearer " + account.accessToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, updated.status, updated.body);
    }

    private record RetainedBytes(byte[] bytes, java.io.IOException failure) {}

    private static RetainedBytes readRetainedResult(InputStream stream) {
        java.io.ByteArrayOutputStream retained = new java.io.ByteArrayOutputStream();
        java.io.IOException failure = null;
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                retained.write(buffer, 0, read);
            }
        } catch (java.io.IOException truncated) {
            failure = truncated;
        }
        return new RetainedBytes(retained.toByteArray(), failure);
    }

    private static byte[] readRetained(InputStream stream) {
        return readRetainedResult(stream).bytes();
    }
}
