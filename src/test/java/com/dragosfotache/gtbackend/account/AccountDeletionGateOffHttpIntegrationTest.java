package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Ordinary production gate-off without any test readiness replacement.
 *
 * <p>A structurally valid new deletion fails closed as 503 without consuming proof,
 * disabling the account, detaching the binding or writing ledger or intent rows.
 * Identical replay and status paths remain independent and are covered in their own
 * explicitly capable suites.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountDeletionGateOffHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "deletion-gateoff-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record Reply(int status, String body, Map<String, java.util.List<String>> headers) {
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

    @Test
    void structurallyValidNewDeleteFailsClosedWithoutStateChange() {
        Reply signupChallenge = post("/v1/auth/google/challenge", "{}", null);
        assertEquals(200, signupChallenge.status, signupChallenge.body);
        String signupId = signupChallenge.node().path("challengeId").asText();
        String signupNonce = signupChallenge.node().path("nonce").asText();
        lenient().when(verifier.verify("gateoff-signup", signupNonce))
                .thenReturn(new Verified("gateoff-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signupId + "\",\"idToken\":\"gateoff-signup\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, grant.body);
        UUID accountId = UUID.fromString(grant.node().path("account").path("id").asText());

        Reply proofChallenge = post("/v1/auth/google/challenge", "{\"purpose\":\"deleteAccount\"}", null);
        assertEquals(200, proofChallenge.status, proofChallenge.body);
        String proofId = proofChallenge.node().path("challengeId").asText();
        String proofNonce = proofChallenge.node().path("nonce").asText();
        lenient().when(verifier.verify("gateoff-token", proofNonce))
                .thenReturn(new Verified("gateoff-subject", null));
        Reply reply = post("/v1/account/delete",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + proofId
                        + "\",\"idToken\":\"gateoff-token\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(503, reply.status, reply.body);
        assertEquals("temporarily_unavailable", reply.node().path("errorCode").asText());
        assertEquals("no-store", reply.cacheControl());
        assertFalse(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, accountId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_deletion_ledger", Integer.class));
    }
}
