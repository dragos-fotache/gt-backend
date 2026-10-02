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
 * Read-side durable-copy winner orders for cursor and snapshot paths.
 *
 * <p>Deletion-first at the read-side pre-fence denies the new copy; holder-first with
 * held SHARE observes acceptance waiting, then releases to commit without a late copy.
 * Existing-token and no-insert paths create no stored copy and keep coherent
 * pre-acceptance semantics while fresh requests deny. The honest scoped-key
 * before-winner case is retained as a supplement in the fences suite. All oracles are
 * hand-authored; gates fail on timeout and are cleaned in finally without circular
 * waits. Snapshot setup reuses an existing cursor to avoid a second SHARE.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionDurableCopyHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "durable-copy-audience");
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
    TestDurableCopyHooks copyHooks;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
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
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject,
                grant.node().path("accessToken").asText());
    }

    @Test
    void cursorDeletionFirstDeniesNewCopyAndHolderFirstCommitsWithoutLateCopy() throws Exception {
        BoundAccount account = provision("copy-cursor-subject", "copy-cursor-signup");
        int cursorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);

        // Deletion-first at the read-side pre-fence: pause the cursor path after RR
        // authorization, commit actual deletion, release and require denial with no copy.
        copyHooks.arm(TestDurableCopyHooks.PauseStage.BEFORE_CURSOR_FENCE, account.accountId);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> read = pool.submit(() -> get("/v1/sync/pull", account.accessToken));
            assertTrue(copyHooks.awaitBeforeCursor(),
                    "cursor read never reached before-fence stage");
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("copy-cursor-delete", proof.nonce))
                    .thenReturn(new Verified("copy-cursor-subject", null));
            Reply deleted = post("/v1/account/delete",
                    "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                            + "\",\"idToken\":\"copy-cursor-delete\",\"confirm\":true}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(202, deleted.status, deleted.body);
            copyHooks.disarm();
            Reply denied = read.get(60, TimeUnit.SECONDS);
            assertEquals(401, denied.status, denied.body);
            assertEquals(cursorsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class,
                    account.accountId));
        } finally {
            copyHooks.disarm();
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotDeletionFirstDeniesNewCopyWithReusedCursorSetup() throws Exception {
        BoundAccount account = provision("copy-snapshot-subject", "copy-snapshot-signup");
        Reply firstPull = get("/v1/sync/pull", account.accessToken);
        assertEquals(200, firstPull.status, firstPull.body);
        int snapshotsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId);

        copyHooks.arm(TestDurableCopyHooks.PauseStage.BEFORE_SNAPSHOT_FENCE, account.accountId);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> read = pool.submit(() -> get("/v1/sync/snapshot?limit=200", account.accessToken));
            assertTrue(copyHooks.awaitBeforeSnapshot(),
                    "snapshot read never reached before-fence stage");
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("copy-snapshot-delete", proof.nonce))
                    .thenReturn(new Verified("copy-snapshot-subject", null));
            Reply deleted = post("/v1/account/delete",
                    "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                            + "\",\"idToken\":\"copy-snapshot-delete\",\"confirm\":true}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(202, deleted.status, deleted.body);
            copyHooks.disarm();
            Reply denied = read.get(60, TimeUnit.SECONDS);
            assertEquals(401, denied.status, denied.body);
            assertEquals(snapshotsBefore, jdbc.queryForObject(
                    "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId));
        } finally {
            copyHooks.disarm();
            pool.shutdownNow();
        }
    }

    @Test
    void cursorHolderFirstBlocksDeletionUntilCopyCommit() throws Exception {
        BoundAccount account = provision("copy-holder-cursor-subject", "copy-holder-cursor-signup");
        int cursorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);
        copyHooks.arm(TestDurableCopyHooks.PauseStage.AFTER_CURSOR_SHARE, account.accountId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> read = pool.submit(() -> get("/v1/sync/pull", account.accessToken));
            assertTrue(copyHooks.awaitAfterCursor(),
                    "cursor read never reached held-share stage");
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("copy-holder-cursor-delete", proof.nonce))
                    .thenReturn(new Verified("copy-holder-cursor-subject", null));
            String key = UUID.randomUUID().toString();
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"copy-holder-cursor-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            Integer readPid = copyHooks.markerPid("afterCursor");
            assertNotNull(readPid, "held-share marker PID was not captured");
            assertTrue(awaitPgBlocking(readPid), "deleting transaction never waited on held read SHARE");
            assertFalse(deletion.isDone(), "deletion must block on held read-side SHARE");
            copyHooks.disarm();
            Reply readOk = read.get(60, TimeUnit.SECONDS);
            assertEquals(200, readOk.status, readOk.body);
            Reply accepted = deletion.get(60, TimeUnit.SECONDS);
            assertEquals(202, accepted.status, accepted.body);
            int cursorsAfter = jdbc.queryForObject(
                    "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class,
                    account.accountId);
            assertTrue(cursorsAfter == cursorsBefore + 1, "holder-first copy commits before deletion");
            assertEquals(401, get("/v1/sync/pull", account.accessToken).status);
        } finally {
            copyHooks.disarm();
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotHolderFirstBlocksDeletionUntilCopyCommitWithReusedCursor() throws Exception {
        BoundAccount account = provision("copy-holder-snapshot-subject", "copy-holder-snapshot-signup");
        Reply firstPull = get("/v1/sync/pull", account.accessToken);
        assertEquals(200, firstPull.status, firstPull.body);
        int snapshotsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId);
        copyHooks.arm(TestDurableCopyHooks.PauseStage.AFTER_SNAPSHOT_SHARE, account.accountId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> read = pool.submit(() -> get("/v1/sync/snapshot?limit=200", account.accessToken));
            assertTrue(copyHooks.awaitAfterSnapshot(),
                    "snapshot read never reached held-share stage");
            ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
            lenient().when(verifier.verify("copy-holder-snapshot-delete", proof.nonce))
                    .thenReturn(new Verified("copy-holder-snapshot-subject", null));
            String key = UUID.randomUUID().toString();
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                    + "\",\"idToken\":\"copy-holder-snapshot-delete\",\"confirm\":true}";
            Future<Reply> deletion = pool.submit(
                    () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            Integer snapshotPid = copyHooks.markerPid("afterSnapshot");
            assertNotNull(snapshotPid, "held-share marker PID was not captured");
            assertTrue(awaitPgBlocking(snapshotPid), "deleting transaction never waited on held snapshot SHARE");
            assertFalse(deletion.isDone(), "deletion must block on held snapshot SHARE");
            copyHooks.disarm();
            Reply readOk = read.get(60, TimeUnit.SECONDS);
            assertEquals(200, readOk.status, readOk.body);
            Reply accepted = deletion.get(60, TimeUnit.SECONDS);
            assertEquals(202, accepted.status, accepted.body);
            assertEquals(snapshotsBefore + 1, jdbc.queryForObject(
                    "SELECT count(*) FROM sync_snapshots WHERE account_id=?", Integer.class, account.accountId));
            assertEquals(401, get("/v1/sync/snapshot?limit=200", account.accessToken).status);
        } finally {
            copyHooks.disarm();
            pool.shutdownNow();
        }
    }

    /**
     * Sequential supplement, not an overlapping admitted read: reuse of an existing token
     * creates no new stored row before deletion, and fresh requests deny afterwards.
     * True overlapping admitted-read coherence is proved by the holder-first cases above.
     */
    @Test
    void existingTokenNoInsertCreatesNoCopyAsSupplement() {
        BoundAccount account = provision("copy-existing-subject", "copy-existing-signup");
        Reply first = get("/v1/sync/pull", account.accessToken);
        assertEquals(200, first.status, first.body);
        String existing = first.node().path("nextCursor").asText();
        int before = jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId);
        Reply reuse = get("/v1/sync/pull?cursor=" + existing, account.accessToken);
        assertEquals(200, reuse.status, reuse.body);
        assertEquals(before, jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId));

        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("copy-existing-delete", proof.nonce))
                .thenReturn(new Verified("copy-existing-subject", null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"copy-existing-delete\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);
        assertEquals(401, get("/v1/sync/pull", account.accessToken).status);
        assertEquals(before, jdbc.queryForObject(
                "SELECT count(*) FROM sync_cursor_tokens WHERE account_id=?", Integer.class, account.accountId));
    }

    private boolean awaitPgBlocking(int blockingPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
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
}
