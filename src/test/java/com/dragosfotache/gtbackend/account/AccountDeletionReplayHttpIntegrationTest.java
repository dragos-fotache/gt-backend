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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9b.3: exact retry and durable receipt for accepted deletion.
 *
 * <p>Retry identity is the exact original UTF-8 body bytes under the scoped
 * {@code (account, deletion key)} identity. Whitespace and property-order differences
 * are different identities. Oracles are hand-authored receipt shapes; the original
 * timestamps never move. Google verification is mocked at the external boundary;
 * PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionReplayHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "deletion-replay-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    GoogleTokenVerifier verifier;

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

    private String deleteBody(String accountId, String challengeId, String token) {
        return "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId
                + "\",\"idToken\":\"" + token + "\",\"confirm\":true}";
    }

    @Test
    void identicalAcceptedRetryReturnsOriginalReceiptWithoutMovingDeadline() {
        BoundAccount account = provision("replay-subject", "replay-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("replay-token", proof.nonce))
                .thenReturn(new Verified("replay-subject", null));
        String key = UUID.randomUUID().toString();
        String body = deleteBody(account.accountId.toString(), proof.id, "replay-token");
        Reply first = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);
        String deletedAt = first.node().path("deletedAt").asText();
        String purgeBy = first.node().path("purgeBy").asText();

        // Identical retry after challenge consumption and binding detachment recovers
        // the exact receipt without provider re-verification dependence.
        Reply retry = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, retry.status, retry.body);
        assertEquals(account.accountId.toString(), retry.node().path("accountId").asText());
        assertEquals("deleted", retry.node().path("state").asText());
        assertEquals(deletedAt, retry.node().path("deletedAt").asText());
        assertEquals(purgeBy, retry.node().path("purgeBy").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
    }

    @Test
    void changedBytesUnderOriginalKeyConflictWithoutEvidenceReplacement() {
        BoundAccount account = provision("conflict-subject", "conflict-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("conflict-token", proof.nonce))
                .thenReturn(new Verified("conflict-subject", null));
        String key = UUID.randomUUID().toString();
        Reply first = post("/v1/account/delete",
                deleteBody(account.accountId.toString(), proof.id, "conflict-token"),
                Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);

        // Whitespace-only difference is a different exact-byte identity.
        Reply whitespace = post("/v1/account/delete",
                "{ \"accountId\" : \"" + account.accountId + "\", \"challengeId\" : \"" + proof.id
                        + "\", \"idToken\" : \"conflict-token\", \"confirm\" : true }",
                Map.of("Idempotency-Key", key));
        assertEquals(409, whitespace.status, whitespace.body);
        assertEquals("idempotency_key_reused", whitespace.node().path("errorCode").asText());

        // Property-order difference is also a different identity.
        ProofChallenge fresh = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("conflict-fresh-token", fresh.nonce))
                .thenReturn(new Verified("conflict-subject", null));
        Reply reordered = post("/v1/account/delete",
                "{\"confirm\":true,\"idToken\":\"conflict-fresh-token\",\"challengeId\":\"" + fresh.id
                        + "\",\"accountId\":\"" + account.accountId + "\"}",
                Map.of("Idempotency-Key", key));
        assertEquals(409, reordered.status, reordered.body);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
    }

    @Test
    void sameUuidKeyInTwoAccountsPermitsIndependentOutcomes() {
        BoundAccount first = provision("two-key-subject-a", "two-key-signup-a");
        BoundAccount second = provision("two-key-subject-b", "two-key-signup-b");
        String sharedKey = UUID.randomUUID().toString();

        ProofChallenge firstProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("two-key-token-a", firstProof.nonce))
                .thenReturn(new Verified("two-key-subject-a", null));
        Reply firstReply = post("/v1/account/delete",
                deleteBody(first.accountId.toString(), firstProof.id, "two-key-token-a"),
                Map.of("Idempotency-Key", sharedKey));
        assertEquals(202, firstReply.status, firstReply.body);

        ProofChallenge secondProof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("two-key-token-b", secondProof.nonce))
                .thenReturn(new Verified("two-key-subject-b", null));
        Reply secondReply = post("/v1/account/delete",
                deleteBody(second.accountId.toString(), secondProof.id, "two-key-token-b"),
                Map.of("Idempotency-Key", sharedKey));
        assertEquals(202, secondReply.status, secondReply.body);
        assertEquals(second.accountId.toString(), secondReply.node().path("accountId").asText());
        assertEquals(first.accountId.toString(), firstReply.node().path("accountId").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                first.accountId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                second.accountId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE deletion_key=?::uuid", Integer.class,
                UUID.fromString(sharedKey)));
    }

    @Test
    void concurrentIdenticalRequestsYieldOneAcceptanceAndImmutableReceipt() throws Exception {
        BoundAccount account = provision("concurrent-subject", "concurrent-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("concurrent-token", proof.nonce))
                .thenReturn(new Verified("concurrent-subject", null));
        String key = UUID.randomUUID().toString();
        String body = deleteBody(account.accountId.toString(), proof.id, "concurrent-token");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> one = pool.submit(() -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            Future<Reply> two = pool.submit(() -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
            Reply first = one.get(60, TimeUnit.SECONDS);
            Reply second = two.get(60, TimeUnit.SECONDS);
            assertEquals(202, first.status, first.body);
            assertEquals(202, second.status, second.body);
            assertEquals(first.node().path("deletedAt").asText(), second.node().path("deletedAt").asText());
            assertEquals(first.node().path("purgeBy").asText(), second.node().path("purgeBy").asText());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                    account.accountId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeyAgainstOwnedDeletedAccountIsInvalidStateWithoutSecondRecord() {
        BoundAccount account = provision("invalid-state-subject", "invalid-state-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("invalid-state-token", proof.nonce))
                .thenReturn(new Verified("invalid-state-subject", null));
        String firstKey = UUID.randomUUID().toString();
        Reply first = post("/v1/account/delete",
                deleteBody(account.accountId.toString(), proof.id, "invalid-state-token"),
                Map.of("Idempotency-Key", firstKey));
        assertEquals(202, first.status, first.body);

        // A different key with fresh proof for the already-deleted owned account does
        // not create a second ledger record and does not consume the fresh challenge.
        ProofChallenge fresh = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("invalid-state-fresh", fresh.nonce))
                .thenReturn(new Verified("invalid-state-subject", null));
        Reply second = post("/v1/account/delete",
                deleteBody(account.accountId.toString(), fresh.id, "invalid-state-fresh"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(409, second.status, second.body);
        assertEquals("invalid_state", second.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class,
                account.accountId));
        assertFalse(Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(fresh.id))));
    }
}
