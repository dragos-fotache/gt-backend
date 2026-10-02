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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9c.7: minimal evidence, structure, and operational limits.
 *
 * <p>The permanent ledger exposes exactly the whitelisted columns and holds no
 * raw identity, credential, profile, payload, or attempt data. Lifecycle
 * responses, including errors, are sanitised with {@code no-store} and carry
 * only shared error codes. A rejected deletion writes no ledger row and leaves
 * the live account untouched.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeLedgerMinimalityHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SYNTHETIC_RING =
            "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}";
    private static final Set<String> LEDGER_WHITELIST = Set.of(
            "old_account_id", "subject_digest_version", "subject_digest", "deleted_at",
            "purge_by", "deletion_key", "request_digest_version", "request_digest");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "purge-minimality-audience");
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

    private UUID signup(String subject, String token) {
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

    @Test
    void ledgerExposesOnlyWhitelistedColumns() {
        List<String> columns = jdbc.query(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema='public' AND table_name='account_deletion_ledger'",
                (rs, row) -> rs.getString(1));
        assertEquals(LEDGER_WHITELIST, new HashSet<>(columns),
                "permanent evidence must contain exactly the whitelist, no domain or credential copy");
    }

    @Test
    void ledgerHoldsNoRawIdentityOrPayloadAfterPurge() {
        String subject = "purge-minimality-subject";
        UUID accountA = signup(subject, "purge-minimality-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        String rawToken = "purge-minimality-token";
        lenient().when(verifier.verify(rawToken, proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"" + rawToken + "\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);
        assertFalse(deleted.body.contains(rawToken));

        purgeDriver.purgeOnce(accountA);

        String subjectHex = jdbc.queryForObject(
                "SELECT encode(subject_digest,'hex') FROM account_deletion_ledger WHERE old_account_id=?",
                String.class, accountA);
        StringBuilder rawHex = new StringBuilder();
        for (byte b : subject.getBytes(StandardCharsets.UTF_8)) {
            rawHex.append(String.format("%02x", b));
        }
        assertFalse(subjectHex.equalsIgnoreCase(rawHex.toString()),
                "the ledger must hold a keyed digest, never the raw subject");
        assertEquals(32, jdbc.queryForObject(
                "SELECT octet_length(subject_digest) FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, accountA));
        assertEquals(32, jdbc.queryForObject(
                "SELECT octet_length(request_digest) FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, accountA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT subject_digest_version FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, accountA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT request_digest_version FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, accountA));
        assertEquals(accountA, jdbc.queryForObject(
                "SELECT old_account_id FROM account_deletion_ledger WHERE old_account_id=?",
                UUID.class, accountA));
    }

    @Test
    void rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive() {
        UUID accountA = signup("purge-minimality-live", "purge-minimality-live-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-minimality-wrong-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("some-other-subject", null));
        Reply rejected = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-minimality-wrong-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertTrue(rejected.status == 401 || rejected.status == 404, rejected.body);
        assertEquals("no-store", rejected.cacheControl());
        assertFalse(rejected.body.contains("purge-minimality-wrong-token"));

        // The class database is shared across tests, so both oracles are scoped
        // to the rejected account instead of counting global rows.
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_purge_intents WHERE old_account_id=?",
                Integer.class, accountA));
        assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, accountA));
    }
}
