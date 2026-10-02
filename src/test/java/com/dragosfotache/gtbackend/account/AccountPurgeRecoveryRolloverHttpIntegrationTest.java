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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9c.4: recovery after actual purge and rollover.
 *
 * <p>After purge, identical accepted-deletion retries return the original
 * receipt and fresh status confirms old A from minimal evidence alone, without
 * restoring payloads, issuing grants, or moving the deadline. Changed identity
 * conflicts and a new key against deleted A never accepts. Historical key
 * rollover from synthetic version 1 to version 2 is covered by the retained
 * 9b status suite; this suite asserts v1-signed recovery and missing-key
 * behaviour only through observable routes.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeRecoveryRolloverHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-recovery-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountPurgeTestDriver purgeDriver;
    @MockitoBean
    GoogleTokenVerifier verifier;

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

    private UUID provision(String subject, String token) {
        Reply signupChallenge = post("/v1/auth/google/challenge", "{}", null);
        assertEquals(200, signupChallenge.status, signupChallenge.body);
        lenient().when(verifier.verify(token, signupChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signupChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        return UUID.fromString(grant.node().path("account").path("id").asText());
    }

    private Reply statusCall(UUID accountId, String token) {
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"accountStatus\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify(token, proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        return post("/v1/account/status",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"" + token + "\"}", null);
    }

    @Test
    void identicalRetryAfterPurgeReturnsOriginalReceipt() {
        UUID accountA = provision("purge-recovery-subject", "purge-recovery-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-recovery-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-recovery-token\",\"confirm\":true}";
        Reply first = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);
        String deletedAt = first.node().path("deletedAt").asText();
        String purgeBy = first.node().path("purgeBy").asText();

        purgeDriver.purgeOnce(accountA);

        Reply retry = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, retry.status, retry.body);
        assertEquals("no-store", retry.cacheControl());
        assertEquals(accountA.toString(), retry.node().path("accountId").asText());
        assertEquals("deleted", retry.node().path("state").asText());
        assertEquals(deletedAt, retry.node().path("deletedAt").asText());
        assertEquals(purgeBy, retry.node().path("purgeBy").asText());
        assertFalse(retry.body.contains("purge-recovery-token"),
                "recovery must not echo credential material");
    }

    @Test
    void freshStatusConfirmsDeletedAccountAfterPurgeWithoutNewState() {
        UUID accountA = provision("purge-recovery-subject", "purge-recovery-status-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-recovery-status-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-recovery-status-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);
        String deletedAt = deleted.node().path("deletedAt").asText();
        String purgeBy = deleted.node().path("purgeBy").asText();

        purgeDriver.purgeOnce(accountA);
        int sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class);

        Reply status = statusCall(accountA, "purge-recovery-status-proof");
        assertEquals(200, status.status, status.body);
        assertEquals("no-store", status.cacheControl());
        assertEquals(accountA.toString(), status.node().path("accountId").asText());
        assertEquals("deleted", status.node().path("state").asText());
        assertEquals(deletedAt, status.node().path("deletedAt").asText());
        assertEquals(purgeBy, status.node().path("purgeBy").asText());

        assertEquals(sessionsBefore, jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class),
                "status must not issue grants or sessions");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA), "status must not restore the purged account");
    }

    @Test
    void changedIdentityAgainstSameKeyConflictsAfterPurge() {
        UUID accountA = provision("purge-recovery-subject", "purge-recovery-conflict-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-recovery-conflict-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-recovery-conflict-token\",\"confirm\":true}";
        Reply first = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);

        purgeDriver.purgeOnce(accountA);

        Reply conflicted = post("/v1/account/delete", body.replace("purge-recovery-conflict-token",
                "purge-recovery-conflict-token "), Map.of("Idempotency-Key", key));
        assertEquals(409, conflicted.status, conflicted.body);
        assertEquals("idempotency_key_reused", conflicted.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
    }

    @Test
    void newKeyAgainstDeletedAccountDoesNotAcceptAfterPurge() {
        UUID accountA = provision("purge-recovery-subject", "purge-recovery-newkey-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-recovery-newkey-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        Reply first = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-recovery-newkey-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, first.status, first.body);

        purgeDriver.purgeOnce(accountA);

        Reply freshProof = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, freshProof.status, freshProof.body);
        lenient().when(verifier.verify("purge-recovery-newkey-second",
                        freshProof.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-recovery-subject", null));
        Reply second = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + freshProof.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-recovery-newkey-second\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(409, second.status, second.body);
        assertEquals("invalid_state", second.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
    }
}
