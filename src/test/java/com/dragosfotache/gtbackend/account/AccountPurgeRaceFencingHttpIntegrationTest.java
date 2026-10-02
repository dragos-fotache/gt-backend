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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9c.6: integrated races and no late copies.
 *
 * <p>Writes racing deletion acceptance are denied without side effects, and
 * purge afterwards still drains every account-bearing store: no post-disablement
 * write commits and no new durable copy appears after completion. Concurrent
 * identical deletes yield one acceptance and one immutable receipt. Gates are
 * the existing deterministic deletion hooks with brief real lock waits and no
 * artificial circular wait.
 *
 * <p>Google verification is mocked at the external boundary with synthetic
 * tokens; PostgreSQL is isolated Testcontainers with real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountPurgeRaceFencingHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "purge-races-audience");
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
    TestAccountDeletionHooks deletionHooks;
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

    @Test
    void postAcceptanceWriteIsDeniedAndPurgeStillCompletes() {
        UUID accountA = signup("purge-races-subject", "purge-races-signup");
        String bearer = bearerFor(accountA);
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-races-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-races-subject", null));
        Reply deleted = post("/v1/account/delete",
                "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                        + proofChallenge.node().path("challengeId").asText()
                        + "\",\"idToken\":\"purge-races-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(202, deleted.status, deleted.body);

        Reply late = authed(bearer, "POST", "/v1/exercises", UUID.randomUUID().toString(),
                "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Late write\"}");
        assertTrue(late.status / 100 != 2, late.body);

        purgeDriver.purgeOnce(accountA);

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM sync_change_batches WHERE account_id=?", Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_mutations WHERE owner_id=?",
                Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exercises WHERE owner_id=?",
                Integer.class, accountA));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
    }

    @Test
    void concurrentIdenticalDeletesYieldOneLedgerRow() throws Exception {
        UUID accountA = signup("purge-races-race", "purge-races-race-signup");
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-races-race-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-races-race", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-races-race-token\",\"confirm\":true}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> first = pool.submit(() -> post("/v1/account/delete", body,
                    Map.of("Idempotency-Key", key)));
            Future<Reply> second = pool.submit(() -> post("/v1/account/delete", body,
                    Map.of("Idempotency-Key", key)));
            Reply r1 = first.get(60, TimeUnit.SECONDS);
            Reply r2 = second.get(60, TimeUnit.SECONDS);
            assertEquals(202, r1.status, r1.body);
            assertEquals(202, r2.status, r2.body);
            assertEquals(r1.node().path("deletedAt").asText(), r2.node().path("deletedAt").asText());
            assertEquals(r1.node().path("purgeBy").asText(), r2.node().path("purgeBy").asText());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));

        purgeDriver.purgeOnce(accountA);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id=?",
                Integer.class, accountA));
    }

    @Test
    void gatedAcceptanceLetsNonContendingCreateSerializeThenPurgeDrainsIt() throws Exception {
        // A routine create takes none of deletion's scoped keys, so the held gate
        // does not deny it. Both orders are valid serializations; purge afterwards
        // must still drain the created-then-deleted routine. The 201 shape follows
        // the accepted resource contract, not observed behavior.
        UUID accountA = signup("purge-races-gate", "purge-races-gate-signup");
        String bearer = bearerFor(accountA);
        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        lenient().when(verifier.verify("purge-races-gate-token",
                        proofChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("purge-races-gate", null));
        String key = UUID.randomUUID().toString();
        String body = "{\"accountId\":\"" + accountA + "\",\"challengeId\":\""
                + proofChallenge.node().path("challengeId").asText()
                + "\",\"idToken\":\"purge-races-gate-token\",\"confirm\":true}";
        String routine = UUID.randomUUID().toString();

        deletionHooks.armScopedKeyGate();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Reply> deletion = pool.submit(
                () -> post("/v1/account/delete", body, Map.of("Idempotency-Key", key)));
        try {
            assertTrue(deletionHooks.awaitScopedKeyEntered(), "deletion must reach the scoped-key gate");
            Reply racing = authed(bearer, "POST", "/v1/routines", UUID.randomUUID().toString(),
                    "{\"id\":\"" + routine + "\"}");
            assertEquals(201, racing.status, racing.body);
            assertEquals(routine, racing.node().path("id").asText());
            assertTrue(racing.node().path("isDraft").asBoolean());
            assertEquals(1, racing.node().path("revision").asLong());
            deletionHooks.releaseScopedKey();
            Reply done = deletion.get(60, TimeUnit.SECONDS);
            assertEquals(202, done.status, done.body);
        } finally {
            deletionHooks.releaseScopedKey();
            pool.shutdownNow();
        }

        purgeDriver.purgeOnce(accountA);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM routines WHERE owner_id=?",
                Integer.class, accountA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger WHERE old_account_id=?", Integer.class, accountA));
    }
}
