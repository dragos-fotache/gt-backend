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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9c.2: deadline, bounds, and actual scheduling.
 *
 * <p>The receipt deadline is exactly {@code deletedAt + 86,400 seconds}, recorded
 * once and never moved. The scheduled purge path is the production entry point,
 * an empty backlog is a safe no-op, and a larger account drains within finite
 * invocations. Capacity beyond the tested backlog remains an explicit release
 * question; these tests never claim an unconditional production guarantee.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeDeadlineSchedulingHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-deadline-audience");
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

    private Reply deleteAccount(UUID accountId, String subject, String token) {
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify(token, proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        return post("/v1/account/delete",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"" + token + "\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    @Test
    void receiptDeadlineIsExactly86400SecondsAfterAcceptanceAndNeverMoves() {
        UUID accountA = provision("purge-deadline-subject", "purge-deadline-signup");
        Reply deleted = deleteAccount(accountA, "purge-deadline-subject", "purge-deadline-token");
        assertEquals(202, deleted.status, deleted.body);
        Instant deletedAt = Instant.parse(deleted.node().path("deletedAt").asText());
        Instant purgeBy = Instant.parse(deleted.node().path("purgeBy").asText());
        assertEquals(86400L, Duration.between(deletedAt, purgeBy).getSeconds(),
                "purgeBy must equal deletedAt plus exactly 86,400 seconds");

        Timestamp ledgerDeleted = jdbc.queryForObject(
                "SELECT deleted_at FROM account_deletion_ledger WHERE old_account_id=?",
                Timestamp.class, accountA);
        Timestamp ledgerPurgeBy = jdbc.queryForObject(
                "SELECT purge_by FROM account_deletion_ledger WHERE old_account_id=?",
                Timestamp.class, accountA);
        assertEquals(deletedAt, ledgerDeleted.toInstant());
        assertEquals(purgeBy, ledgerPurgeBy.toInstant());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger"
                        + " WHERE purge_by <> deleted_at + interval '86400 seconds'",
                Integer.class));

        purgeDriver.purgeOnce(accountA);

        Timestamp keptPurgeBy = jdbc.queryForObject(
                "SELECT purge_by FROM account_deletion_ledger WHERE old_account_id=?",
                Timestamp.class, accountA);
        assertEquals(purgeBy, keptPurgeBy.toInstant(), "retries and purge must not move the deadline");
    }

    @Test
    void scheduledPurgeOnEmptyBacklogIsSafeNoOp() {
        UUID accountA = provision("purge-deadline-empty", "purge-deadline-empty-signup");

        purgeDriver.purgeDue();

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_deletion_ledger", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA));
    }

    @Test
    void largerAccountDrainsWithinFiniteInvocations() {
        UUID accountA = provision("purge-deadline-large", "purge-deadline-large-signup");
        // The applied schema allows only one live active workout per account, so
        // the bulk footprint is one active row plus finished rows with finished_at.
        jdbc.update("INSERT INTO workouts(id,owner_id,revision,name,started_at,status)"
                + " VALUES(?,?,1,'Bulk active',clock_timestamp(),'active')",
                UUID.randomUUID(), accountA);
        for (int i = 0; i < 11; i++) {
            jdbc.update("INSERT INTO workouts(id,owner_id,revision,name,started_at,finished_at,status)"
                    + " VALUES(?,?,1,?,clock_timestamp(),clock_timestamp(),'finished')",
                    UUID.randomUUID(), accountA, "Bulk " + i);
        }
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE owner_id=?",
                Integer.class, accountA));

        Reply deleted = deleteAccount(accountA, "purge-deadline-large", "purge-deadline-large-token");
        assertEquals(202, deleted.status, deleted.body);

        purgeDriver.purgeOnce(accountA);
        purgeDriver.purgeOnce(accountA);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE owner_id=?",
                Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM root_id_reservations WHERE owner_id=?", Integer.class, accountA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
    }
}
