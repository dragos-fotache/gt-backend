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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Pool/fault boundary: under a finite pool with bounded acquisition, concurrent exports must
 * terminate fail-closed (complete success, sanitized pre-byte error with durable consumption,
 * or post-byte abort) without hangs, synthetic partial success, or leaked connections.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportPoolHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("auth.google-audience", () -> "export-pool-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
    private record Reply(int status, byte[] bytes) {
        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Reply post(String path, String body, String key) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
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
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " failed: " + route, e);
        }
    }

    private BoundAccount provision(String subject, String token) {
        Reply challenge = post("/v1/auth/google/challenge", "{}", UUID.randomUUID().toString());
        assertEquals(200, challenge.status, challenge.body());
        JsonNode node;
        try {
            node = JSON.readTree(challenge.body());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        lenient().when(verifier.verify(token, node.path("nonce").asText())).thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + node.path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}",
                UUID.randomUUID().toString());
        assertEquals(200, grant.status, grant.body());
        JsonNode grantNode;
        try {
            grantNode = JSON.readTree(grant.body());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new BoundAccount(UUID.fromString(grantNode.path("account").path("id").asText()), subject,
                grantNode.path("accessToken").asText());
    }

    private String exportBody(BoundAccount account, String tokenName) {
        Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        assertEquals(200, challenge.status, challenge.body());
        JsonNode node;
        try {
            node = JSON.readTree(challenge.body());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        lenient().when(verifier.verify(tokenName, node.path("nonce").asText())).thenReturn(new Verified(account.subject, null));
        return "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                + node.path("challengeId").asText() + "\",\"idToken\":\"" + tokenName + "\"}";
    }

    private void seedWideData(BoundAccount account) {
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Pool base\"}").status);
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Pool workout\",\"startedAt\":\"2026-09-07T06:00:00Z\"}").status);
        StringBuilder children = new StringBuilder("[");
        for (int index = 0; index < 25; index++) {
            if (index > 0) {
                children.append(",");
            }
            children.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"exerciseId\":\"").append(custom)
                    .append("\",\"position\":").append(index).append(",\"notes\":\"")
                    .append("pool-").append(index).append("-".repeat(1500))
                    .append("\",\"restDurationSeconds\":10,\"sets\":[{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"position\":0,\"repetitions\":5,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":10}]}");
        }
        children.append("]");
        assertEquals(200, authed(account.accessToken, "PUT", "/v1/workouts/" + workout,
                "{\"id\":\"" + workout + "\",\"name\":\"Pool workout\",\"status\":\"active\","
                        + "\"startedAt\":\"2026-09-07T06:00:00Z\",\"exercises\":" + children + "}").status);
    }

    @Test
    void concurrentExportsUnderFinitePoolTerminateFailClosedWithoutLeaks() throws Exception {
        BoundAccount first = provision("pool-first-subject", "pool-first-signup");
        BoundAccount second = provision("pool-second-subject", "pool-second-signup");
        seedWideData(first);
        seedWideData(second);
        String one = exportBody(first, "pool-token-one");
        String two = exportBody(second, "pool-token-two");

        Future<Reply> left;
        Future<Reply> right;
        try (var pool = Executors.newFixedThreadPool(2)) {
            left = pool.submit(() -> post("/v1/account/export", one, null));
            right = pool.submit(() -> post("/v1/account/export", two, null));
            Reply oneReply = left.get(120, TimeUnit.SECONDS);
            Reply twoReply = right.get(120, TimeUnit.SECONDS);
            for (Reply reply : new Reply[]{oneReply, twoReply}) {
                if (reply.status == 200) {
                    JsonNode root = JSON.readTree(reply.body());
                    assertEquals(1, root.path("exportVersion").asInt());
                    assertTrue(root.path("workouts").size() >= 1, "successful export must be complete");
                } else {
                    assertTrue(reply.status == 500 || reply.status == 503,
                            "pool pressure must fail closed with 500/503, got " + reply.status);
                    assertFalse(reply.body().contains("customExercises"),
                            "failed export must release no export data");
                }
            }
        }

        HikariDataSource hikari = (HikariDataSource) dataSource;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (hikari.getHikariPoolMXBean().getActiveConnections() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections(), "pool must drain after contention");
        Reply probe = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        assertEquals(200, probe.status, "pool must remain usable after contention");
    }
}
