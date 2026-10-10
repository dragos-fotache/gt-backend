package com.dragosfotache.gtbackend.auth;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.CHALLENGE_LIFETIME_SECONDS;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.assertLosslessMillis;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Contract-derived regression for auth expiry precision on challenge issuance.
 *
 * <p>Source clock carries nonzero microsecond and nanosecond fractions. Every fresh
 * challenge deadline (default purpose plus all four explicit purposes) must be a
 * losslessly millisecond-representable UTC instant with the exact five-minute
 * lifetime, and stored deadlines must equal returned deadlines. Expiry uses
 * {@code now >= expiresAt}: exchange 1ns before the deadline succeeds, at the
 * exact deadline it fails without consuming the challenge.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthChallengePrecisionHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Instant BASE = Instant.parse("2026-10-04T12:00:00.123456789Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "challenge-precision-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("auth.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("auth.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("sync.history.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("sync.history.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("sync.snapshot.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("sync.snapshot.cleanup.fixed-delay-ms", () -> "3600000");
        registry.add("account.deletion.purge.initial-delay-ms", () -> "3600000");
        registry.add("account.deletion.purge.fixed-delay-ms", () -> "3600000");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthPrecisionClock clock;
    @Autowired
    AuthService authService;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class AuthPrecisionClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(BASE);

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
    static class AuthPrecisionClockConfiguration {
        @Bean
        @Primary
        AuthPrecisionClock authPrecisionClock() {
            return new AuthPrecisionClock();
        }
    }

    private record Reply(int status, String body) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON");
            }
        }
    }

    private Reply post(String path, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path);
        }
    }

    @Test
    void defaultAndAllPurposesAreMillisecondPreciseWithExactLifetimeAndStoredEquality() {
        String[] bodies = {"{}", "{\"purpose\":\"signIn\"}", "{\"purpose\":\"export\"}",
                "{\"purpose\":\"deleteAccount\"}", "{\"purpose\":\"accountStatus\"}"};
        String[] expectedPurposes = {"signIn", "signIn", "export", "deleteAccount", "accountStatus"};
        Instant expectedIssued = truncated(BASE);
        for (int i = 0; i < bodies.length; i++) {
            Reply reply = post("/v1/auth/google/challenge", bodies[i], null);
            assertEquals(200, reply.status, "challenge issuance failed");
            Instant expires = AuthExpiryPrecisionSupport.parseExpiry("challenge", reply.node(), "expiresAt");
            assertLosslessMillis(expires, "challenge expiresAt");
            assertEquals(expectedIssued.plusSeconds(CHALLENGE_LIFETIME_SECONDS), expires,
                    "challenge lifetime must be exactly five minutes from the truncated issuance instant");
            UUID id;
            try {
                id = UUID.fromString(reply.node().path("challengeId").asText());
            } catch (Exception e) {
                throw new AssertionError("challengeId is not a UUID");
            }
            Timestamp storedCreated = jdbc.queryForObject(
                    "SELECT created_at FROM auth_challenges WHERE id=?", Timestamp.class, id);
            Timestamp storedExpires = jdbc.queryForObject(
                    "SELECT expires_at FROM auth_challenges WHERE id=?", Timestamp.class, id);
            assertTrue(storedCreated != null && storedExpires != null, "challenge row must be persisted");
            assertEquals(expectedIssued, storedCreated.toInstant(), "stored creation must equal truncated issuance");
            assertEquals(expires, storedExpires.toInstant(), "stored deadline must equal returned deadline");
            String storedPurpose = jdbc.queryForObject(
                    "SELECT purpose::text FROM auth_challenges WHERE id=?", String.class, id);
            assertEquals(expectedPurposes[i], storedPurpose, "stored challenge purpose must match request");
        }
        Reply invalid = post("/v1/auth/google/challenge", "{\"purpose\":\"unknown\"}", null);
        assertEquals(422, invalid.status, "unknown challenge purpose must be rejected");
    }

    @Test
    void exchangeOneNanosecondBeforeDeadlineSucceedsAndAtExactDeadlineFailsUnconsumed() {
        try {
            Reply first = post("/v1/auth/google/challenge", "{}", null);
            assertEquals(200, first.status, "challenge issuance failed");
            Instant firstExpiry = AuthExpiryPrecisionSupport.parseExpiry("challenge", first.node(), "expiresAt");
            String firstId = first.node().path("challengeId").asText();
            String firstNonce = first.node().path("nonce").asText();
            when(verifier.verify("precision-before", firstNonce))
                    .thenReturn(new Verified("precision-before-subject", null));
            clock.set(firstExpiry.minusNanos(1));
            Reply before = post("/v1/auth/google",
                    "{\"challengeId\":\"" + firstId + "\",\"idToken\":\"precision-before\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, before.status, "exchange 1ns before challenge expiry must succeed");
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                    Boolean.class, UUID.fromString(firstId))), "accepted challenge must be consumed");

            Reply second = post("/v1/auth/google/challenge", "{}", null);
            assertEquals(200, second.status, "challenge issuance failed");
            Instant secondExpiry = AuthExpiryPrecisionSupport.parseExpiry("challenge", second.node(), "expiresAt");
            String secondId = second.node().path("challengeId").asText();
            String secondNonce = second.node().path("nonce").asText();
            when(verifier.verify("precision-equal", secondNonce))
                    .thenReturn(new Verified("precision-equal-subject", null));
            clock.set(secondExpiry);
            Reply equal = post("/v1/auth/google",
                    "{\"challengeId\":\"" + secondId + "\",\"idToken\":\"precision-equal\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(401, equal.status, "exchange at exact challenge expiry must be rejected");
            assertEquals("invalid_google_credential", equal.node().path("errorCode").asText(),
                    "expired challenge must report invalid_google_credential");
            assertTrue(Boolean.FALSE.equals(jdbc.queryForObject(
                    "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                    Boolean.class, UUID.fromString(secondId))), "rejected challenge must stay unconsumed");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void cleanupAtOneNanosecondBeforeExpiryRetainsAndAtEqualExpiryRemovesChallenge() {
        Reply reply = post("/v1/auth/google/challenge", "{}", null);
        assertEquals(200, reply.status, "challenge issuance failed");
        Instant expiry = AuthExpiryPrecisionSupport.parseExpiry("challenge", reply.node(), "expiresAt");
        UUID id = UUID.fromString(reply.node().path("challengeId").asText());
        try {
            clock.set(expiry.minusNanos(1));
            authService.cleanupExpired();
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_challenges WHERE id=?", Integer.class, id),
                    "cleanup before expiry must retain the unconsumed challenge");
            clock.set(expiry);
            authService.cleanupExpired();
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_challenges WHERE id=?", Integer.class, id),
                    "cleanup at exact expiry must remove the challenge");
        } finally {
            clock.set(BASE);
        }
    }
}
