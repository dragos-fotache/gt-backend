package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9b.1: proof, confirmation and release gate for {@code POST /v1/account/delete}.
 *
 * <p>Expected wire shapes and error codes are hand-authored from OpenAPI and the
 * account lifecycle contract. Google verification is mocked at the external boundary;
 * PostgreSQL is an isolated Testcontainers instance with real HTTP.
 *
 * <p>Ordinary production readiness is unavailable: a new valid delete fails closed
 * without acceptance. Isolated acceptance tests enable the gate through an explicit
 * test-classpath-only replacement after approved design; this file covers the
 * gate-off path and all rejection paths that must leave proof and state unchanged.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestPurgeReadinessReplacement.class)
class AccountDeletionProofHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "deletion-proof-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys", () -> SYNTHETIC_RING);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock proofClock;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void set(Instant instant) {
            now.set(instant);
        }
    }

    @TestConfiguration
    static class ProofClockConfiguration {
        @Bean
        @Primary
        MutableClock proofClock() {
            return new MutableClock();
        }
    }

    private record BoundAccount(UUID accountId, String subject, String accessToken, String refreshToken) {}
    private record ProofChallenge(String id, String nonce) {}
    private record Reply(int status, String body, Map<String, List<String>> headers) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body, e);
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

        String cacheControl() {
            return header("Cache-Control");
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

    private ProofChallenge challenge(String purposeBody) {
        Reply reply = post("/v1/auth/google/challenge", purposeBody, null);
        assertEquals(200, reply.status, reply.body);
        assertEquals("no-store", reply.cacheControl());
        return new ProofChallenge(reply.node().path("challengeId").asText(), reply.node().path("nonce").asText());
    }

    private BoundAccount provision(String subject, String token) {
        ProofChallenge signup = challenge("{}");
        lenient().when(verifier.verify(token, signup.nonce)).thenReturn(new Verified(subject, null));
        String key = UUID.randomUUID().toString();
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"" + token + "\"}",
                Map.of("Idempotency-Key", key));
        assertEquals(200, grant.status, grant.body);
        return new BoundAccount(
                UUID.fromString(grant.node().path("account").path("id").asText()),
                subject,
                grant.node().path("accessToken").asText(),
                grant.node().path("refreshToken").asText());
    }

    private Reply deleteCall(String accountId, String challengeId, String token, String confirmJson,
                             String deletionKey) {
        String body = "{\"accountId\":\"" + accountId + "\",\"challengeId\":\"" + challengeId
                + "\",\"idToken\":\"" + token + "\",\"confirm\":" + confirmJson + "}";
        return post("/v1/account/delete", body, Map.of("Idempotency-Key", deletionKey));
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private boolean accountDisabled(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT deleted_at IS NOT NULL FROM accounts WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), accountId));
    }

    private int ledgerRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_ledger", Integer.class);
    }

    @Test
    void bearerAloneWithoutFreshProofIsDenied() {
        BoundAccount account = provision("bearer-delete-subject", "bearer-delete-signup");
        String key = UUID.randomUUID().toString();
        Reply missing = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"confirm\":true}",
                Map.of("Idempotency-Key", key, "Authorization", "Bearer " + account.accessToken));
        assertEquals(401, missing.status, missing.body);
        assertEquals("no-store", missing.cacheControl());
        assertFalse(accountDisabled(account.accountId));
    }

    @Test
    void wrongPurposeProofIsRejectedWithoutConsumption() {
        BoundAccount account = provision("purpose-delete-subject", "purpose-delete-signup");
        ProofChallenge export = challenge("{\"purpose\":\"export\"}");
        ProofChallenge signIn = challenge("{\"purpose\":\"signIn\"}");
        ProofChallenge status = challenge("{\"purpose\":\"accountStatus\"}");
        String key = UUID.randomUUID().toString();

        lenient().when(verifier.verify("wrong-purpose-token", export.nonce))
                .thenReturn(new Verified("purpose-delete-subject", null));
        Reply reply = deleteCall(account.accountId.toString(), export.id, "wrong-purpose-token", "true", key);
        assertEquals(401, reply.status, reply.body);
        assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
        assertEquals("no-store", reply.cacheControl());
        assertFalse(challengeConsumed(export.id));

        lenient().when(verifier.verify("signin-purpose-token", signIn.nonce))
                .thenReturn(new Verified("purpose-delete-subject", null));
        assertEquals(401, deleteCall(account.accountId.toString(), signIn.id,
                "signin-purpose-token", "true", UUID.randomUUID().toString()).status);
        lenient().when(verifier.verify("status-purpose-token", status.nonce))
                .thenReturn(new Verified("purpose-delete-subject", null));
        assertEquals(401, deleteCall(account.accountId.toString(), status.id,
                "status-purpose-token", "true", UUID.randomUUID().toString()).status);
        assertFalse(challengeConsumed(signIn.id));
        assertFalse(challengeConsumed(status.id));
        assertFalse(accountDisabled(account.accountId));
        assertEquals(0, ledgerRows());
    }

    @Test
    void wrongSubjectAndForeignAccountAreNotFoundWithoutConsumption() {
        BoundAccount account = provision("owner-delete-subject", "owner-delete-signup");
        BoundAccount other = provision("other-delete-subject", "other-delete-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("foreign-delete-token", proof.nonce))
                .thenReturn(new Verified("other-delete-subject", null));
        Reply foreign = deleteCall(account.accountId.toString(), proof.id, "foreign-delete-token",
                "true", UUID.randomUUID().toString());
        assertEquals(404, foreign.status, foreign.body);
        assertEquals("not_found", foreign.node().path("errorCode").asText());
        assertFalse(challengeConsumed(proof.id));

        ProofChallenge second = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("wrong-account-token", second.nonce))
                .thenReturn(new Verified("owner-delete-subject", null));
        Reply wrongAccount = deleteCall(other.accountId.toString(), second.id, "wrong-account-token",
                "true", UUID.randomUUID().toString());
        assertEquals(404, wrongAccount.status, wrongAccount.body);
        assertFalse(challengeConsumed(second.id));
        assertFalse(accountDisabled(account.accountId));
        assertFalse(accountDisabled(other.accountId));
    }

    @Test
    void exactProofExpiryRejectsAtBoundaryWithoutConsumption() {
        BoundAccount account = provision("expiry-delete-subject", "expiry-delete-signup");
        ProofChallenge expired = challenge("{\"purpose\":\"deleteAccount\"}");
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM auth_challenges WHERE id=?",
                java.sql.Timestamp.class, UUID.fromString(expired.id)).toInstant();
        proofClock.set(expires);
        lenient().when(verifier.verify("expiry-delete-token", expired.nonce))
                .thenReturn(new Verified("expiry-delete-subject", null));
        Reply rejected = deleteCall(account.accountId.toString(), expired.id, "expiry-delete-token",
                "true", UUID.randomUUID().toString());
        assertEquals(401, rejected.status, rejected.body);
        assertEquals("invalid_google_credential", rejected.node().path("errorCode").asText());
        assertFalse(challengeConsumed(expired.id));
        assertFalse(accountDisabled(account.accountId));
        proofClock.set(Instant.now());
    }

    @Test
    void missingAndFalseConfirmationAreValidationErrorsWithoutConsumption() {
        BoundAccount account = provision("confirm-delete-subject", "confirm-delete-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        lenient().when(verifier.verify("confirm-token", proof.nonce))
                .thenReturn(new Verified("confirm-delete-subject", null));

        Reply missing = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"confirm-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(422, missing.status, missing.body);
        assertEquals("validation_error", missing.node().path("errorCode").asText());

        Reply falseConfirm = deleteCall(account.accountId.toString(), proof.id, "confirm-token",
                "false", UUID.randomUUID().toString());
        assertEquals(422, falseConfirm.status, falseConfirm.body);
        assertEquals("validation_error", falseConfirm.node().path("errorCode").asText());

        Reply nonBoolean = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"confirm-token\",\"confirm\":\"yes\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(422, nonBoolean.status, nonBoolean.body);

        assertFalse(challengeConsumed(proof.id));
        assertFalse(accountDisabled(account.accountId));
        assertEquals(0, ledgerRows());
    }

    @Test
    void malformedJsonDuplicateKeysUnknownFieldsAndKeyShapeAreRejected() {
        BoundAccount account = provision("shape-delete-subject", "shape-delete-signup");
        ProofChallenge proof = challenge("{\"purpose\":\"deleteAccount\"}");
        assertEquals(400, post("/v1/account/delete", "{not-json",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).status);
        Reply duplicate = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"accountId\":\"" + account.accountId
                        + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"x\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(400, duplicate.status, duplicate.body);
        Reply unknown = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"x\",\"confirm\":true,\"purgeNow\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(422, unknown.status, unknown.body);
        assertEquals("validation_error", unknown.node().path("errorCode").asText());
        Reply upper = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId.toString().toUpperCase() + "\",\"challengeId\":\""
                        + proof.id + "\",\"idToken\":\"x\",\"confirm\":true}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(422, upper.status, upper.body);
        Reply badKey = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"x\",\"confirm\":true}",
                Map.of("Idempotency-Key", "not-a-uuid"));
        assertEquals(400, badKey.status, badKey.body);
        Reply missingKey = post("/v1/account/delete",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + proof.id
                        + "\",\"idToken\":\"x\",\"confirm\":true}", null);
        assertEquals(400, missingKey.status, missingKey.body);
        assertFalse(challengeConsumed(proof.id));
        for (Reply reply : List.of(duplicate, unknown, upper, badKey, missingKey)) {
            assertEquals("no-store", reply.cacheControl());
        }
    }
}
