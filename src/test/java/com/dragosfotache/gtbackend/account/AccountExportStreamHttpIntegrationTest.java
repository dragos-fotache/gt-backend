package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * AC9a.3: stream headers, large aggregates without truncation, data minimization,
 * no replay namespace, no durable side effects, and connection release.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportStreamHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "revision", "mutationId", "cursor", "nextCursor", "ownerId", "sessionId",
            "fingerprint", "refreshToken", "accessToken", "credential", "accountId");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "export-stream-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @MockitoSpyBean
    DataSource spyDataSource;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
    private record Reply(int status, byte[] bytes, Map<String, List<String>> headers) {
        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        JsonNode node() {
            try {
                return JSON.readTree(body());
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body(), e);
            }
        }

        String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
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
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private Reply authed(String bearer, String method, String route, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .header("Idempotency-Key", UUID.randomUUID().toString());
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " failed: " + route, e);
        }
    }

    private BoundAccount provision(String subject, String token) {
        Reply challenge = post("/v1/auth/google/challenge", "{}", null);
        assertEquals(200, challenge.status, challenge.body());
        lenient().when(verifier.verify(token, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body());
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject,
                grant.node().path("accessToken").asText());
    }

    private Reply exportFor(BoundAccount account, String tokenName, Map<String, String> extraHeaders) {
        Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        assertEquals(200, challenge.status, challenge.body());
        lenient().when(verifier.verify(tokenName, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(account.subject, null));
        return post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                        + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + tokenName + "\"}",
                extraHeaders);
    }

    private static void assertNoForbiddenKeys(JsonNode node, boolean isRoot) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (!(isRoot && entry.getKey().equals("accountId"))) {
                    assertFalse(FORBIDDEN_KEYS.contains(entry.getKey()), "forbidden internal field: " + entry.getKey());
                }
                assertNoForbiddenKeys(entry.getValue(), false);
            });
        } else if (node.isArray()) {
            node.forEach(child -> assertNoForbiddenKeys(child, false));
        }
    }

    @Test
    void successAndRepresentativeErrorsCarryNoStoreAndSanitizedBodies() {
        BoundAccount account = provision("headers-subject", "headers-signup");
        Reply ok = exportFor(account, "headers-token", null);
        assertEquals(200, ok.status, ok.body());
        assertEquals("no-store", ok.header("Cache-Control"));
        assertNotNull(ok.header("Content-Disposition"));
        assertTrue(ok.header("Content-Disposition").contains("gymtracka-export.json"));
        assertTrue(ok.header("Content-Type").contains("application/json"));

        Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        lenient().when(verifier.verify("headers-wrong-purpose", challenge.node().path("nonce").asText()))
                .thenReturn(new Verified("headers-subject", null));
        Reply wrongPurpose = post("/v1/auth/google/challenge", "{\"purpose\":\"signIn\"}", null);
        Reply bad = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                        + wrongPurpose.node().path("challengeId").asText() + "\",\"idToken\":\"secret-proof-token\"}", null);
        assertEquals(401, bad.status, bad.body());
        assertEquals("no-store", bad.header("Cache-Control"));
        assertFalse(bad.body().contains("secret-proof-token"), "error must not echo the credential");

        Reply foreignChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        lenient().when(verifier.verify("secret-foreign-token", foreignChallenge.node().path("nonce").asText()))
                .thenReturn(new Verified("headers-stranger-subject", null));
        Reply foreign = post("/v1/account/export",
                "{\"accountId\":\"" + UUID.randomUUID() + "\",\"challengeId\":\""
                        + foreignChallenge.node().path("challengeId").asText() + "\",\"idToken\":\"secret-foreign-token\"}", null);
        assertEquals(404, foreign.status, foreign.body());
        assertEquals("no-store", foreign.header("Cache-Control"));
        assertFalse(foreign.body().contains("secret-foreign-token"));
    }

    @Test
    void largeCompleteAggregateStreamsWithoutTruncationOrCaps() {
        BoundAccount account = provision("large-subject", "large-signup");
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Large base\"}").status);

        int exercises = 40;
        int setsPerExercise = 8;
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Large workout\",\"startedAt\":\"2026-09-03T06:00:00Z\"}").status);
        StringBuilder children = new StringBuilder("[");
        for (int index = 0; index < exercises; index++) {
            String entry = UUID.randomUUID().toString();
            String note = ("note-" + index + "-").repeat(300);
            children.append("{\"id\":\"").append(entry).append("\",\"exerciseId\":\"").append(custom)
                    .append("\",\"position\":").append(index)
                    .append(",\"notes\":\"").append(note).append("\",\"restDurationSeconds\":").append(index % 120)
                    .append(",\"sets\":[");
            for (int set = 0; set < setsPerExercise; set++) {
                if (set > 0) {
                    children.append(",");
                }
                children.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"position\":").append(set)
                        .append(",\"repetitions\":").append(set % 2 == 0 ? String.valueOf(5 + set) : "null")
                        .append(",\"weightKg\":").append(set % 2 == 0 ? (set % 3 == 0 ? "0" : "42.5") : "0")
                        .append(",\"isCompleted\":").append(set % 2 == 0)
                        .append(",\"restSeconds\":").append(set % 2 == 0 ? "30" : "null").append("}");
            }
            children.append("]}");
            if (index < exercises - 1) {
                children.append(",");
            }
        }
        children.append("]");
        Reply put = authed(account.accessToken, "PUT", "/v1/workouts/" + workout,
                "{\"id\":\"" + workout + "\",\"name\":\"Large workout\",\"status\":\"active\","
                        + "\"startedAt\":\"2026-09-03T06:00:00Z\",\"exercises\":" + children + "}");
        assertEquals(200, put.status, put.body());

        Reply reply = exportFor(account, "large-token", null);
        assertEquals(200, reply.status, "large export failed at " + reply.bytes.length + " bytes");
        assertTrue(reply.bytes.length > 8 * 1024, "export must span several 8 KiB gate segments, got " + reply.bytes.length);
        JsonNode root = reply.node();
        JsonNode exported = null;
        for (JsonNode candidate : root.path("workouts")) {
            if (workout.equals(candidate.path("id").asText())) {
                exported = candidate;
            }
        }
        assertNotNull(exported, "large root missing from export");
        assertEquals(exercises, exported.path("exercises").size());
        for (int index = 0; index < exercises; index++) {
            JsonNode entry = exported.path("exercises").get(index);
            assertEquals(index, entry.path("position").asInt());
            assertEquals(setsPerExercise, entry.path("sets").size());
            assertEquals(("note-" + index + "-").repeat(300), entry.path("notes").asText());
            assertEquals("Large base", entry.path("exerciseName").asText());
        }
        assertNoForbiddenKeys(root, true);
    }

    @Test
    void unrelatedIdempotencyHeaderCreatesNoReplayNamespace() {
        BoundAccount account = provision("idempotent-header-subject", "idempotent-header-signup");
        Map<String, String> header = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(200, exportFor(account, "header-token-one", header).status);
        // Same header value with a fresh challenge must succeed independently, not replay or conflict.
        assertEquals(200, exportFor(account, "header-token-two", header).status);
    }

    @Test
    void exportCreatesNoDomainReplayCredentialOrRevisionSideEffects() {
        BoundAccount account = provision("side-effect-subject", "side-effect-signup");
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Side effect base\"}").status);
        String routine = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status);
        long batchesBefore = jdbc.queryForObject("SELECT count(*) FROM sync_change_batches WHERE account_id=?", Long.class, account.accountId);
        long mutationsBefore = jdbc.queryForObject("SELECT count(*) FROM processed_mutations WHERE owner_id=?", Long.class, account.accountId);
        long sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE account_id=?", Long.class, account.accountId);
        long credentialsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id WHERE s.account_id=?", Long.class, account.accountId);
        long idempotencyBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_idempotency i JOIN auth_sessions s ON s.id=i.session_id WHERE s.account_id=?", Long.class, account.accountId);

        assertEquals(200, exportFor(account, "side-effect-token", null).status);

        assertEquals(batchesBefore, jdbc.queryForObject("SELECT count(*) FROM sync_change_batches WHERE account_id=?", Long.class, account.accountId));
        assertEquals(mutationsBefore, jdbc.queryForObject("SELECT count(*) FROM processed_mutations WHERE owner_id=?", Long.class, account.accountId));
        assertEquals(sessionsBefore, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE account_id=?", Long.class, account.accountId));
        assertEquals(credentialsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id WHERE s.account_id=?", Long.class, account.accountId));
        assertEquals(idempotencyBefore, jdbc.queryForObject(
                "SELECT count(*) FROM auth_idempotency i JOIN auth_sessions s ON s.id=i.session_id WHERE s.account_id=?", Long.class, account.accountId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_idempotency WHERE operation NOT IN ('google','refresh')", Integer.class));
    }

    @Test
    void borrowedConnectionsAreReleasedAfterExport() throws Exception {
        BoundAccount account = provision("pool-subject", "pool-signup");
        assertEquals(200, exportFor(account, "pool-token", null).status);
        verify(spyDataSource, atLeast(3)).getConnection();
        HikariDataSource pool = (HikariDataSource) dataSource;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (pool.getHikariPoolMXBean().getActiveConnections() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), "export must release every borrowed connection");
        List<String> ids = new ArrayList<>();
        jdbc.query("SELECT id::text FROM accounts", (rs, row) -> ids.add(rs.getString(1)));
        assertFalse(ids.isEmpty(), "pool must remain usable after export");
    }
}
