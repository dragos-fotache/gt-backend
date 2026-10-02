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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9c.3: crash, rollback, and durable resume.
 *
 * <p>Purge of an unknown account is a safe no-op that touches nothing.
 * Repeated purge invocations are idempotent: the original receipt, deadline,
 * and ledger row never change and completed work never regresses. The scheduled
 * path after completion is stable. Phase-level fault injection inside purge
 * internals awaits the Worker's purge-phase hooks and is recorded as an
 * explicit gap, not claimed by these tests.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeCrashResumeHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-resume-audience");
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
            return new Reply(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8));
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

    @Test
    void unknownAccountPurgeFailsClosedWithoutDeletes() {
        // The approved design fail-closes purge with no intent row as 500
        // internal_error with no deletes. The driver surfaces the production
        // failure as a RuntimeException carrying internal_error; the oracle is
        // the fail-closed signal plus unchanged state, never the exception type.
        UUID live = provision("purge-resume-live", "purge-resume-live-signup");
        UUID unknown = UUID.randomUUID();

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> purgeDriver.purgeOnce(unknown));
        assertTrue(carriesInternalError(thrown),
                "purge with no intent row must fail closed as internal_error");

        // The class database is shared across tests, so every ledger oracle is
        // scoped to the accounts under test instead of counting global rows.
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, unknown));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?",
                Integer.class, live));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, live));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_sync_state WHERE account_id=?",
                Integer.class, live));
    }

    private static boolean carriesInternalError(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("internal_error")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void repeatedPurgeIsIdempotentWithUnchangedReceipt() {
        UUID accountA = provision("purge-resume-subject", "purge-resume-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-resume-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-resume-subject", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-resume-token\",\"confirm\":true}";
        Reply first = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);
        String deletedAt = first.node().path("deletedAt").asText();
        String purgeBy = first.node().path("purgeBy").asText();

        purgeDriver.purgeOnce(accountA);
        purgeDriver.purgeOnce(accountA);

        Reply retry = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, retry.status, retry.body);
        assertEquals(accountA.toString(), retry.node().path("accountId").asText());
        assertEquals("deleted", retry.node().path("state").asText());
        assertEquals(deletedAt, retry.node().path("deletedAt").asText());
        assertEquals(purgeBy, retry.node().path("purgeBy").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA));
    }

    @Test
    void scheduledPurgeAfterCompletionIsStableNoOp() {
        UUID accountA = provision("purge-resume-done", "purge-resume-done-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-resume-done-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-resume-done", null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-resume-done-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);

        int catalogueBefore = jdbc.queryForObject(
                "SELECT count(*) FROM exercises WHERE owner_id IS NULL", Integer.class);
        purgeDriver.purgeOnce(accountA);
        purgeDriver.purgeDue();

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
        assertEquals(catalogueBefore, jdbc.queryForObject(
                "SELECT count(*) FROM exercises WHERE owner_id IS NULL", Integer.class));
    }
}
