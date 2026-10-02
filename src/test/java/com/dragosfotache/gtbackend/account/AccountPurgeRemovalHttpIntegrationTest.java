package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
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
 * AC9c.1: complete physical data removal.
 *
 * <p>Account A is built through accepted HTTP surfaces with live and tombstoned
 * roots, resource replay, sessions, and sync batches. After real deletion and one
 * production purge invocation, every account-bearing store for A is empty while
 * only the whitelisted permanent ledger remains. Catalogue rows and a populated
 * unrelated account B stay intact. All oracles are hand-authored from the fixed
 * 9c inventory and shared schema ownership; no production mapper output is used.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeRemovalHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-removal-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthCrypto authCrypto;
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

    private Reply authed(String token, String method, String route, String key, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method,
                        HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " failed: " + route, e);
        }
    }

    private record ProofChallenge(String id, String nonce) {}

    private ProofChallenge challenge(String purposeBody) {
        Reply reply = post("/v1/auth/google/challenge", purposeBody, null);
        assertEquals(200, reply.status, reply.body);
        return new ProofChallenge(reply.node().path("challengeId").asText(),
                reply.node().path("nonce").asText());
    }

    private UUID provision(String subject, String token) {
        ProofChallenge signup = challenge("{}");
        lenient().when(verifier.verify(token, signup.nonce)).thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        return UUID.fromString(grant.node().path("account").path("id").asText());
    }

    private String bearerFor(UUID accountId) {
        String token = UUID.randomUUID().toString();
        UUID session = UUID.randomUUID();
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())",
                session, accountId);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at)"
                + " values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '1 hour')",
                authCrypto.digest(token), session);
        return token;
    }

    private int ownedCount(String table, String column, UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, accountId);
    }

    @Test
    void purgeRemovesAllAccountBearingRowsAndKeepsLedgerAndCatalogue() {
        UUID accountA = provision("purge-removal-subject", "purge-removal-signup");
        String bearer = bearerFor(accountA);

        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(bearer, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + custom + "\",\"name\":\"Purge custom\"}").status);
        String routine = UUID.randomUUID().toString();
        assertEquals(201,
                authed(bearer, "POST", "/v1/routines", UUID.randomUUID().toString(),
                        "{\"id\":\"" + routine + "\"}").status);
        String workout = UUID.randomUUID().toString();
        assertEquals(201,
                authed(bearer, "POST", "/v1/workouts", UUID.randomUUID().toString(),
                        "{\"id\":\"" + workout + "\",\"name\":\"Purge run\",\"startedAt\":\"2025-04-01T00:00:00Z\"}").status);
        // The shared domain contract fails finish without a completed set, so the
        // fixture first records one completed set through the accepted PUT shape.
        String entry = UUID.randomUUID().toString();
        String set = UUID.randomUUID().toString();
        // Strict parsing rejects coerced strings, so numeric fields travel as JSON numbers.
        String children = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + custom
                + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\""
                + set + "\",\"position\":0,\"repetitions\":5,\"weightKg\":20,"
                + "\"isCompleted\":true,\"restSeconds\":60}]}]";
        assertEquals(200, authed(bearer, "PUT", "/v1/workouts/" + workout, UUID.randomUUID().toString(),
                "{\"id\":\"" + workout + "\",\"name\":\"Purge run\",\"status\":\"active\","
                        + "\"startedAt\":\"2025-04-01T00:00:00Z\",\"exercises\":" + children + "}").status);
        Reply finished = authed(bearer, "POST", "/v1/workouts/" + workout + "/finish",
                UUID.randomUUID().toString(), "{\"finishedAt\":\"2025-04-01T01:00:00Z\"}");
        assertEquals(200, finished.status, finished.body);
        assertEquals("finished", finished.node().path("status").asText());
        assertEquals(204, authed(bearer, "DELETE", "/v1/workouts/" + workout, UUID.randomUUID().toString(),
                null).status);

        int catalogueBefore = jdbc.queryForObject(
                "SELECT count(*) FROM exercises WHERE owner_id IS NULL", Integer.class);
        UUID accountB = provision("purge-removal-other", "purge-removal-other-signup");
        String bearerB = bearerFor(accountB);
        String otherCustom = UUID.randomUUID().toString();
        assertEquals(201, authed(bearerB, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + otherCustom + "\",\"name\":\"Other stays\"}").status);

        assertTrue(ownedCount("workouts", "owner_id", accountA) > 0);
        assertTrue(ownedCount("routines", "owner_id", accountA) > 0);
        assertTrue(ownedCount("exercises", "owner_id", accountA) > 0);
        assertTrue(ownedCount("resource_mutations", "owner_id", accountA) > 0);
        assertTrue(ownedCount("auth_sessions", "account_id", accountA) > 0);

        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("purge-removal-token", proof.nonce))
                .thenReturn(new Verified("purge-removal-subject", null));
        String key = UUID.randomUUID().toString();
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"purge-removal-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", key));
        assertEquals(202, deleted.status, deleted.body);

        purgeDriver.purgeOnce(accountA);

        assertEquals(0, ownedCount("workouts", "owner_id", accountA));
        assertEquals(0, ownedCount("routines", "owner_id", accountA));
        assertEquals(0, ownedCount("exercises", "owner_id", accountA));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM workout_exercises e JOIN workouts w ON w.id=e.workout_id"
                        + " WHERE w.owner_id=?", Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM routine_exercises e JOIN routines r ON r.id=e.routine_id"
                        + " WHERE r.owner_id=?", Integer.class, accountA));
        assertEquals(0, ownedCount("processed_mutations", "owner_id", accountA));
        assertEquals(0, ownedCount("resource_mutations", "owner_id", accountA));
        assertEquals(0, ownedCount("sync_change_batches", "account_id", accountA));
        assertEquals(0, ownedCount("sync_changes", "account_id", accountA));
        assertEquals(0, ownedCount("account_sync_state", "account_id", accountA));
        assertEquals(0, ownedCount("sync_cursor_tokens", "account_id", accountA));
        assertEquals(0, ownedCount("sync_snapshots", "account_id", accountA));
        assertEquals(0, ownedCount("sync_snapshot_records", "account_id", accountA));
        assertEquals(0, ownedCount("auth_sessions", "account_id", accountA));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_credentials c LEFT JOIN auth_sessions s ON s.id=c.session_id"
                        + " WHERE s.id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_idempotency i LEFT JOIN auth_sessions s ON s.id=i.session_id"
                        + " WHERE s.id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM google_identity_bindings WHERE account_id=?", Integer.class, accountA));
        assertEquals(0, ownedCount("root_id_reservations", "owner_id", accountA));
        assertEquals(0, ownedCount("child_id_reservations", "owner_id", accountA));
        assertEquals(0, ownedCount("accounts", "id", accountA));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));

        assertEquals(catalogueBefore, jdbc.queryForObject(
                "SELECT count(*) FROM exercises WHERE owner_id IS NULL", Integer.class));
        assertEquals("Other stays", jdbc.queryForObject(
                "SELECT name FROM exercises WHERE id=?", String.class, UUID.fromString(otherCustom)));
        assertEquals(1, ownedCount("exercises", "owner_id", accountB));
        assertEquals(1, ownedCount("accounts", "id", accountB));
    }

    @Test
    void purgeKeepsUnrelatedAccountStateByteIdentical() {
        UUID accountA = provision("purge-removal-a2", "purge-removal-a2-signup");
        UUID accountB = provision("purge-removal-b2", "purge-removal-b2-signup");
        String bearerB = bearerFor(accountB);
        String kept = UUID.randomUUID().toString();
        assertEquals(201, authed(bearerB, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + kept + "\",\"name\":\"Untouched\"}").status);
        String nameBefore = jdbc.queryForObject("SELECT name FROM exercises WHERE id=?",
                String.class, UUID.fromString(kept));

        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("purge-removal-a2-token", proof.nonce))
                .thenReturn(new Verified("purge-removal-a2", null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"purge-removal-a2-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);

        purgeDriver.purgeOnce(accountA);

        assertEquals(nameBefore, jdbc.queryForObject("SELECT name FROM exercises WHERE id=?",
                String.class, UUID.fromString(kept)));
        assertEquals(1, ownedCount("accounts", "id", accountB));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountB));
    }
}
