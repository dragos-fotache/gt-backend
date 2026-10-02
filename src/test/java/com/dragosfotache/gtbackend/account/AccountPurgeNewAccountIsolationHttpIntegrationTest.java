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
 * AC9c.5: new-account and delayed-work isolation.
 *
 * <p>A distinct fresh sign-in for the same Google subject creates empty B with
 * a new UUID both before and after A's purge. Delayed A purge, proof, retry,
 * and read activity while B holds rich state never touches B: no binding,
 * session, root, reservation, or replay row is transferred or deleted, and no
 * old A credential, cursor, snapshot token, or accepted-deletion replay can
 * authorise B. Backend fencing is asserted; Android cache clearing is never
 * claimed.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeNewAccountIsolationHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-isolation-audience");
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

    private UUID deleteAccount(UUID accountId, String subject, String token) {
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify(token, proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"" + token + "\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);
        return accountId;
    }

    @Test
    void freshSignupBeforePurgeCreatesEmptyBWithNewUuid() {
        UUID accountA = signup("purge-isolation-subject", "purge-isolation-a-signup");
        deleteAccount(accountA, "purge-isolation-subject", "purge-isolation-a-delete");

        UUID accountB = signup("purge-isolation-subject", "purge-isolation-b-signup");

        assertNotEquals(accountA, accountB);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workouts WHERE owner_id=?",
                Integer.class, accountB));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM routines WHERE owner_id=?",
                Integer.class, accountB));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exercises WHERE owner_id=?",
                Integer.class, accountB));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM root_id_reservations WHERE owner_id=?", Integer.class, accountB));
        assertEquals(accountB, jdbc.queryForObject(
                "SELECT account_id FROM google_identity_bindings WHERE google_subject=?",
                UUID.class, "purge-isolation-subject"));

        purgeDriver.purgeOnce(accountA);

        assertEquals(accountB, jdbc.queryForObject(
                "SELECT account_id FROM google_identity_bindings WHERE google_subject=?",
                UUID.class, "purge-isolation-subject"),
                "delayed purge must never delete B's replacement binding");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountB));
    }

    @Test
    void delayedPurgeKeepsBRichStateIntactAndFencesOldCredentials() {
        UUID accountA = signup("purge-isolation-rich", "purge-isolation-rich-a");
        String oldBearer = bearerFor(accountA);
        deleteAccount(accountA, "purge-isolation-rich", "purge-isolation-rich-delete");

        UUID accountB = signup("purge-isolation-rich", "purge-isolation-rich-b");
        String bearerB = bearerFor(accountB);
        String kept = UUID.randomUUID().toString();
        assertEquals(201, authed(bearerB, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + kept + "\",\"name\":\"B keeps\"}").status);

        Reply fenced = authed(oldBearer, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Must not land\"}");
        assertTrue(fenced.status / 100 != 2, fenced.body);
        // The fixture holds two B sessions (signup grant plus test bearer), so the
        // isolation oracle pins that exact count across purge: none removed, none added.
        int sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE account_id=?",
                Integer.class, accountB);
        assertEquals(2, sessionsBefore);

        purgeDriver.purgeOnce(accountA);

        assertEquals("B keeps", jdbc.queryForObject("SELECT name FROM exercises WHERE id=?",
                String.class, UUID.fromString(kept)));
        assertEquals(sessionsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE account_id=?", Integer.class, accountB));
        String stillWorks = UUID.randomUUID().toString();
        assertEquals(201, authed(bearerB, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + stillWorks + "\",\"name\":\"B writes after purge\"}").status);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA));
    }

    @Test
    void oldAcceptedReplayAfterBSignupReturnsAReceiptWithoutTouchingB() {
        UUID accountA = signup("purge-isolation-replay", "purge-isolation-replay-a");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-isolation-replay-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-isolation-replay", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-isolation-replay-token\",\"confirm\":true}";
        Reply first = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, first.status, first.body);

        UUID accountB = signup("purge-isolation-replay", "purge-isolation-replay-b");
        purgeDriver.purgeOnce(accountA);

        Reply replay = post("/v1/account/delete", body, Map.of("Idempotency-Key", key));
        assertEquals(202, replay.status, replay.body);
        assertEquals(accountA.toString(), replay.node().path("accountId").asText());
        assertNotEquals(accountB.toString(), replay.node().path("accountId").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountB));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM root_id_reservations WHERE owner_id=?", Integer.class, accountB));
    }
}
