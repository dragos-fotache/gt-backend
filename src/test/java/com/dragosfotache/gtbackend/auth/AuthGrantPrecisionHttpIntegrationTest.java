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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.ACCESS_LIFETIME_SECONDS;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.REFRESH_LIFETIME_DAYS;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.assertCredentialBytesEqual;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.assertLosslessMillis;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.isLosslessMillis;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.parseExpiry;
import static com.dragosfotache.gtbackend.auth.AuthExpiryPrecisionSupport.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Contract-derived regression for auth grant expiry precision.
 *
 * <p>Source clock carries nonzero microsecond and nanosecond fractions. Fresh exchange
 * and refresh rotation must return losslessly millisecond-representable deadlines with
 * exact 15-minute / 30-day lifetimes from a single truncated issuance instant; stored
 * issued/expiry/replay values must equal returned values. Identical key+body replay
 * returns the original bytes without rotation or extension, including with expired
 * access while refresh is current. Boundary checks use {@code now >= expiresAt} for
 * challenge/access (read and write)/refresh/replay/logout. Lock-wait expiry uses an
 * externally held row lock plus the controlled clock; no sleeps stand in for expiry.
 * Credential-bearing material never appears in assertion messages.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthGrantPrecisionHttpIntegrationTest {
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
        registry.add("auth.google-audience", () -> "grant-precision-audience");
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
    AuthGrantPrecisionClock clock;
    @Autowired
    AuthService authService;
    @Autowired
    SessionAuthorization authorization;
    @Autowired
    AuthCrypto crypto;
    @Autowired
    PlatformTransactionManager txManager;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class AuthGrantPrecisionClock extends Clock {
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
    static class AuthGrantPrecisionClockConfiguration {
        @Bean
        @Primary
        AuthGrantPrecisionClock authGrantPrecisionClock() {
            return new AuthGrantPrecisionClock();
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

    private String[] freshChallenge() {
        Reply reply = post("/v1/auth/google/challenge", "{}", null);
        assertEquals(200, reply.status, "challenge issuance failed");
        return new String[]{reply.node().path("challengeId").asText(), reply.node().path("nonce").asText()};
    }

    private int credentialCount(UUID session) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_credentials WHERE session_id=?", Integer.class, session);
    }

    private UUID sessionOfRefresh(String refreshToken) {
        return jdbc.queryForObject("SELECT session_id FROM auth_credentials WHERE fingerprint=?",
                UUID.class, crypto.digest(refreshToken));
    }

    @Test
    void exchangeAndRefreshRotationAreMillisecondPreciseWithExactLifetimesAndPersistence() {
        String[] challenge = freshChallenge();
        when(verifier.verify("grant-lifetime-token", challenge[1]))
                .thenReturn(new Verified("grant-lifetime-subject", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"grant-lifetime-token\"}";
        Reply grant = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        Instant issued = truncated(BASE);
        Instant accessExp = parseExpiry("exchange", grant.node(), "accessExpiresAt");
        Instant refreshExp = parseExpiry("exchange", grant.node(), "refreshExpiresAt");
        assertLosslessMillis(accessExp, "exchange accessExpiresAt");
        assertLosslessMillis(refreshExp, "exchange refreshExpiresAt");
        assertEquals(issued.plusSeconds(ACCESS_LIFETIME_SECONDS), accessExp,
                "access lifetime must be exactly 15 minutes from truncated issuance");
        assertEquals(issued.plus(Duration.ofDays(REFRESH_LIFETIME_DAYS)), refreshExp,
                "refresh lifetime must be exactly 30 days from truncated issuance");

        String refreshToken = grant.node().path("refreshToken").asText();
        String accessToken = grant.node().path("accessToken").asText();
        UUID session = sessionOfRefresh(refreshToken);
        Timestamp accessIssued = jdbc.queryForObject(
                "SELECT issued_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(accessToken));
        Timestamp accessStored = jdbc.queryForObject(
                "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(accessToken));
        Timestamp refreshIssued = jdbc.queryForObject(
                "SELECT issued_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(refreshToken));
        Timestamp refreshStored = jdbc.queryForObject(
                "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(refreshToken));
        assertEquals(issued, accessIssued.toInstant(), "stored access issuance must equal truncated issuance");
        assertEquals(accessExp, accessStored.toInstant(), "stored access deadline must equal returned deadline");
        assertEquals(issued, refreshIssued.toInstant(), "stored refresh issuance must equal truncated issuance");
        assertEquals(refreshExp, refreshStored.toInstant(), "stored refresh deadline must equal returned deadline");
        Timestamp replayStored = jdbc.queryForObject(
                "SELECT expires_at FROM auth_idempotency WHERE operation='google' AND request_key=?",
                Timestamp.class, key);
        assertEquals(refreshExp, replayStored.toInstant(), "stored replay deadline must equal returned refresh deadline");

        Instant advancedIssuance = Instant.parse("2026-10-04T12:01:07.987654321Z");
        try {
            clock.set(advancedIssuance);
            Instant rotatedIssued = truncated(advancedIssuance);
            UUID rotationKey = UUID.randomUUID();
            String rotationBody = "{\"refreshToken\":\"" + refreshToken + "\"}";
            Reply rotated = post("/v1/auth/refresh", rotationBody, Map.of("Idempotency-Key", rotationKey.toString()));
            assertEquals(200, rotated.status, "refresh rotation failed");
            Instant newAccessExp = parseExpiry("refresh", rotated.node(), "accessExpiresAt");
            Instant newRefreshExp = parseExpiry("refresh", rotated.node(), "refreshExpiresAt");
            assertLosslessMillis(newAccessExp, "rotated accessExpiresAt");
            assertLosslessMillis(newRefreshExp, "rotated refreshExpiresAt");
            assertEquals(rotatedIssued.plusSeconds(ACCESS_LIFETIME_SECONDS), newAccessExp,
                    "rotated access lifetime must be exactly 15 minutes from advanced truncated issuance");
            assertEquals(rotatedIssued.plus(Duration.ofDays(REFRESH_LIFETIME_DAYS)), newRefreshExp,
                    "rotated refresh lifetime must be exactly 30 days from advanced truncated issuance");
            assertEquals(grant.node().path("account").path("id").asText(),
                    rotated.node().path("account").path("id").asText(), "rotation must preserve account identity");
            String newAccessToken = rotated.node().path("accessToken").asText();
            String newRefreshToken = rotated.node().path("refreshToken").asText();
            Timestamp newAccessIssued = jdbc.queryForObject(
                    "SELECT issued_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                    crypto.digest(newAccessToken));
            Timestamp newAccessStored = jdbc.queryForObject(
                    "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                    crypto.digest(newAccessToken));
            Timestamp newRefreshStored = jdbc.queryForObject(
                    "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                    crypto.digest(newRefreshToken));
            assertEquals(rotatedIssued, newAccessIssued.toInstant(),
                    "rotated access issuance must equal advanced truncated issuance");
            assertEquals(newAccessExp, newAccessStored.toInstant(),
                    "rotated stored access deadline must equal returned deadline");
            assertEquals(newRefreshExp, newRefreshStored.toInstant(),
                    "rotated stored refresh deadline must equal returned deadline");
            Timestamp rotationReplayStored = jdbc.queryForObject(
                    "SELECT expires_at FROM auth_idempotency WHERE operation='refresh' AND request_key=?",
                    Timestamp.class, rotationKey);
            assertEquals(newRefreshExp, rotationReplayStored.toInstant(),
                    "rotated stored replay deadline must equal returned refresh deadline");
            assertTrue(credentialCount(session) >= 4, "rotation must append a new credential pair to the session");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void exactSameKeyBodyReplayPreservesOriginalGrantWithoutExtension() {
        String[] challenge = freshChallenge();
        when(verifier.verify("replay-token", challenge[1]))
                .thenReturn(new Verified("replay-subject", null));
        UUID exchangeKey = UUID.randomUUID();
        String exchangeBody = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"replay-token\"}";
        Reply grant = post("/v1/auth/google", exchangeBody, Map.of("Idempotency-Key", exchangeKey.toString()));
        assertEquals(200, grant.status, "exchange failed");
        UUID session = sessionOfRefresh(grant.node().path("refreshToken").asText());
        int credentialsBefore = credentialCount(session);
        Instant refreshExp = parseExpiry("exchange", grant.node(), "refreshExpiresAt");

        Reply exchangeReplay = post("/v1/auth/google", exchangeBody,
                Map.of("Idempotency-Key", exchangeKey.toString()));
        assertEquals(200, exchangeReplay.status, "identical exchange replay must succeed");
        assertCredentialBytesEqual(grant.body.getBytes(StandardCharsets.UTF_8),
                exchangeReplay.body.getBytes(StandardCharsets.UTF_8), "exchange replay");
        assertEquals(credentialsBefore, credentialCount(session),
                "exchange replay must not rotate credentials");
        assertEquals(refreshExp, parseExpiry("exchange replay", exchangeReplay.node(), "refreshExpiresAt"),
                "exchange replay must not extend expiry");

        String refreshToken = grant.node().path("refreshToken").asText();
        UUID refreshKey = UUID.randomUUID();
        String refreshBody = "{\"refreshToken\":\"" + refreshToken + "\"}";
        Reply rotated = post("/v1/auth/refresh", refreshBody, Map.of("Idempotency-Key", refreshKey.toString()));
        assertEquals(200, rotated.status, "refresh rotation failed");
        int afterRotation = credentialCount(session);
        Reply refreshReplay = post("/v1/auth/refresh", refreshBody,
                Map.of("Idempotency-Key", refreshKey.toString()));
        assertEquals(200, refreshReplay.status, "identical refresh replay must succeed");
        assertCredentialBytesEqual(rotated.body.getBytes(StandardCharsets.UTF_8),
                refreshReplay.body.getBytes(StandardCharsets.UTF_8), "refresh replay");
        assertEquals(afterRotation, credentialCount(session), "refresh replay must not rotate again");
        assertEquals(parseExpiry("refresh", rotated.node(), "refreshExpiresAt"),
                parseExpiry("refresh replay", refreshReplay.node(), "refreshExpiresAt"),
                "refresh replay must not extend expiry");

        Reply conflict = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"replay-token-changed\"}",
                Map.of("Idempotency-Key", exchangeKey.toString()));
        assertEquals(409, conflict.status, "same key with changed exchange bytes must conflict");
        Reply refreshConflict = post("/v1/auth/refresh", "{\"refreshToken\":\"changed\"}",
                Map.of("Idempotency-Key", refreshKey.toString()));
        assertEquals(409, refreshConflict.status, "same key with changed refresh bytes must conflict");
    }

    @Test
    void expiredAccessWithCurrentRefreshStillReplaysOriginalGrant() {
        String[] challenge = freshChallenge();
        when(verifier.verify("expired-access-token", challenge[1]))
                .thenReturn(new Verified("expired-access-subject", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"expired-access-token\"}";
        Reply grant = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        String accessToken = grant.node().path("accessToken").asText();
        String refreshToken = grant.node().path("refreshToken").asText();
        Instant accessExp = parseExpiry("exchange", grant.node(), "accessExpiresAt");
        try {
            clock.set(accessExp);
            assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccessForRead(accessToken),
                    "expired access must be rejected for reads");

            Reply replay = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
            assertEquals(200, replay.status, "exchange replay with expired access must return the original grant");
            assertCredentialBytesEqual(grant.body.getBytes(StandardCharsets.UTF_8),
                    replay.body.getBytes(StandardCharsets.UTF_8), "expired-access replay");

            UUID rotationKey = UUID.randomUUID();
            Reply rotated = post("/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}",
                    Map.of("Idempotency-Key", rotationKey.toString()));
            assertEquals(200, rotated.status, "refresh with current refresh credential must still rotate");
            assertLosslessMillis(parseExpiry("refresh", rotated.node(), "accessExpiresAt"), "rotated accessExpiresAt");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void refreshExpiryBoundaryOneNanosecondBeforeRotatesAndAtEqualRejects() {
        String[] challenge = freshChallenge();
        when(verifier.verify("refresh-boundary-token", challenge[1]))
                .thenReturn(new Verified("refresh-boundary-subject", null));
        UUID key = UUID.randomUUID();
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"refresh-boundary-token\"}",
                Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        String refreshToken = grant.node().path("refreshToken").asText();
        Instant refreshExp = parseExpiry("exchange", grant.node(), "refreshExpiresAt");
        try {
            clock.set(refreshExp.minusNanos(1));
            Reply before = post("/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, before.status, "refresh 1ns before expiry must rotate");

            clock.set(refreshExp);
            Reply equal = post("/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(401, equal.status, "refresh at exact expiry must be rejected");
            assertEquals("reauthentication_required", equal.node().path("errorCode").asText(),
                    "expired refresh must report reauthentication_required");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void replayExpiryBoundaryOneNanosecondBeforeReplaysAndAtEqualRejects() {
        String[] challenge = freshChallenge();
        when(verifier.verify("replay-boundary-token", challenge[1]))
                .thenReturn(new Verified("replay-boundary-subject", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"replay-boundary-token\"}";
        Reply grant = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        Instant replayDeadline = parseExpiry("exchange", grant.node(), "refreshExpiresAt");
        try {
            clock.set(replayDeadline.minusNanos(1));
            Reply before = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
            assertEquals(200, before.status, "replay 1ns before replay expiry must return the original grant");
            assertCredentialBytesEqual(grant.body.getBytes(StandardCharsets.UTF_8),
                    before.body.getBytes(StandardCharsets.UTF_8), "replay-boundary replay");

            clock.set(replayDeadline);
            Reply equal = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
            assertEquals(401, equal.status, "replay at exact replay expiry must be rejected");
            assertEquals("reauthentication_required", equal.node().path("errorCode").asText(),
                    "expired replay must report reauthentication_required");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void accessReadAndWriteBoundaryOneNanosecondBeforeAuthorizesAndAtEqualRejects() {
        String[] challenge = freshChallenge();
        when(verifier.verify("access-boundary-token", challenge[1]))
                .thenReturn(new Verified("access-boundary-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"access-boundary-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, "exchange failed");
        String accessToken = grant.node().path("accessToken").asText();
        Instant deadline = parseExpiry("exchange", grant.node(), "accessExpiresAt");
        try {
            clock.set(deadline.minusNanos(1));
            authorization.requireLiveAccessForRead(accessToken);
            authorization.requireLiveAccess(accessToken);

            clock.set(deadline);
            assertThrows(AuthService.AuthException.class,
                    () -> authorization.requireLiveAccessForRead(accessToken),
                    "read authorization at exact access expiry must be rejected");
            assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess(accessToken),
                    "write authorization at exact access expiry must be rejected");
        } finally {
            clock.set(BASE);
        }
    }

    @Test
    void logoutOneNanosecondBeforeExpiryRevokesAndExpiredLogoutIsIdempotentNoop() {
        String[] first = freshChallenge();
        when(verifier.verify("logout-live-token", first[1]))
                .thenReturn(new Verified("logout-live-subject", null));
        Reply live = post("/v1/auth/google",
                "{\"challengeId\":\"" + first[0] + "\",\"idToken\":\"logout-live-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, live.status, "exchange failed");
        String liveRefresh = live.node().path("refreshToken").asText();
        String liveAccess = live.node().path("accessToken").asText();
        UUID liveSession = sessionOfRefresh(liveRefresh);
        Instant liveDeadline = parseExpiry("exchange", live.node(), "refreshExpiresAt");

        Instant secondIssuance = Instant.parse("2026-10-04T12:02:11.555666777Z");
        clock.set(secondIssuance);
        String[] second = freshChallenge();
        when(verifier.verify("logout-expired-token", second[1]))
                .thenReturn(new Verified("logout-expired-subject", null));
        Reply expired = post("/v1/auth/google",
                "{\"challengeId\":\"" + second[0] + "\",\"idToken\":\"logout-expired-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, expired.status, "exchange failed");
        String expiredRefresh = expired.node().path("refreshToken").asText();
        String expiredAccess = expired.node().path("accessToken").asText();
        UUID expiredSession = sessionOfRefresh(expiredRefresh);
        Instant expiredDeadline = parseExpiry("exchange", expired.node(), "refreshExpiresAt");
        try {
            clock.set(liveDeadline.minusNanos(1));
            Reply revoked = post("/v1/auth/logout", "{\"refreshToken\":\"" + liveRefresh + "\"}", null);
            assertEquals(204, revoked.status, "logout 1ns before refresh expiry must be accepted");
            assertTrue(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=?",
                    Boolean.class, liveSession), "accepted logout must revoke the session");
            assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess(liveAccess),
                    "access of a logged-out session must be rejected");

            clock.set(expiredDeadline);
            Reply noop = post("/v1/auth/logout", "{\"refreshToken\":\"" + expiredRefresh + "\"}", null);
            assertEquals(204, noop.status, "logout with an expired credential must still return 204");
            assertFalse(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=?",
                    Boolean.class, expiredSession), "expired logout must not revoke the session");
            clock.set(BASE);
            authorization.requireLiveAccessForRead(expiredAccess);
        } finally {
            clock.set(BASE);
        }

        Reply unknown = post("/v1/auth/logout", "{\"refreshToken\":\"not-a-known-credential\"}", null);
        assertEquals(204, unknown.status, "unknown logout must return 204 without revealing existence");
    }

    @Test
    void invalidRefreshAndConsumedFamilyRegression() {
        Reply unknown = post("/v1/auth/refresh", "{\"refreshToken\":\"not-a-known-credential\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, unknown.status, "unknown refresh must be rejected");
        assertEquals("reauthentication_required", unknown.node().path("errorCode").asText(),
                "unknown refresh must report reauthentication_required");

        String[] first = freshChallenge();
        when(verifier.verify("family-first-token", first[1]))
                .thenReturn(new Verified("family-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + first[0] + "\",\"idToken\":\"family-first-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, "exchange failed");
        String oldRefresh = grant.node().path("refreshToken").asText();
        UUID session = sessionOfRefresh(oldRefresh);

        String[] second = freshChallenge();
        when(verifier.verify("family-second-token", second[1]))
                .thenReturn(new Verified("family-subject", null));
        Reply other = post("/v1/auth/google",
                "{\"challengeId\":\"" + second[0] + "\",\"idToken\":\"family-second-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, other.status, "second session exchange failed");
        String otherAccess = other.node().path("accessToken").asText();

        Reply rotated = post("/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, rotated.status, "refresh rotation failed");

        Reply reuse = post("/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, reuse.status, "consumed refresh under a different key must be rejected");
        assertEquals("reauthentication_required", reuse.node().path("errorCode").asText(),
                "consumed refresh reuse must report reauthentication_required");
        assertTrue(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=?",
                Boolean.class, session), "consumed refresh reuse must revoke its token family");
        authorization.requireLiveAccessForRead(otherAccess);

        Reply consumedChallenge = post("/v1/auth/google",
                "{\"challengeId\":\"" + first[0] + "\",\"idToken\":\"family-first-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(401, consumedChallenge.status, "consumed challenge under a different key must be rejected");
    }

    @Test
    void grandfatheredHighPrecisionReplayIsPreservedAndReportedWhileNewRefreshIsCompatible() throws Exception {
        String[] challenge = freshChallenge();
        when(verifier.verify("legacy-precision-token", challenge[1]))
                .thenReturn(new Verified("legacy-precision-subject", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"legacy-precision-token\"}";
        Reply grant = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        String accountId = grant.node().path("account").path("id").asText();
        String accessToken = grant.node().path("accessToken").asText();
        String refreshToken = grant.node().path("refreshToken").asText();
        Instant accessExp = parseExpiry("exchange", grant.node(), "accessExpiresAt");
        Instant refreshExp = parseExpiry("exchange", grant.node(), "refreshExpiresAt");

        Instant legacyAccess = accessExp.plusNanos(123456);
        Instant legacyRefresh = refreshExp.plusNanos(789012);
        assertTrue(!isLosslessMillis(legacyAccess) && !isLosslessMillis(legacyRefresh),
                "legacy fixture must carry nonzero sub-millisecond precision");
        Map<String, Object> legacyAccount = new LinkedHashMap<>();
        legacyAccount.put("id", accountId);
        Map<String, Object> legacyGrant = new LinkedHashMap<>();
        legacyGrant.put("account", legacyAccount);
        legacyGrant.put("accessToken", accessToken);
        legacyGrant.put("accessExpiresAt", legacyAccess.toString());
        legacyGrant.put("refreshToken", refreshToken);
        legacyGrant.put("refreshExpiresAt", legacyRefresh.toString());
        byte[] legacyBytes = JSON.writeValueAsBytes(legacyGrant);
        byte[] encrypted = crypto.encrypt(legacyBytes);
        jdbc.update("UPDATE auth_idempotency SET encrypted_response=?, expires_at=? "
                        + "WHERE operation='google' AND request_key=?",
                encrypted, Timestamp.from(legacyRefresh), key);
        jdbc.update("UPDATE auth_credentials SET expires_at=? WHERE fingerprint=?",
                Timestamp.from(legacyAccess), crypto.digest(accessToken));
        jdbc.update("UPDATE auth_credentials SET expires_at=? WHERE fingerprint=?",
                Timestamp.from(legacyRefresh), crypto.digest(refreshToken));
        Instant seededAccess = jdbc.queryForObject(
                "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(accessToken)).toInstant();
        Instant seededRefresh = jdbc.queryForObject(
                "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                crypto.digest(refreshToken)).toInstant();
        Instant seededReplay = jdbc.queryForObject(
                "SELECT expires_at FROM auth_idempotency WHERE operation='google' AND request_key=?",
                Timestamp.class, key).toInstant();
        assertTrue(!isLosslessMillis(seededAccess) && !isLosslessMillis(seededRefresh)
                        && !isLosslessMillis(seededReplay),
                "seeded legacy persisted deadlines must retain sub-millisecond precision");

        Reply replayed = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, replayed.status, "grandfathered replay must still return the accepted grant");
        assertCredentialBytesEqual(legacyBytes, replayed.body.getBytes(StandardCharsets.UTF_8),
                "grandfathered replay");
        Instant returnedAccess = parseExpiry("grandfathered replay", replayed.node(), "accessExpiresAt");
        assertEquals(legacyAccess, returnedAccess,
                "grandfathered replay deadline must be preserved verbatim, never rewritten");
        assertTrue(!isLosslessMillis(returnedAccess),
                "grandfathered replay retains sub-millisecond precision: reported here, not silently fixed");
        byte[] stored = jdbc.queryForObject(
                "SELECT encrypted_response FROM auth_idempotency WHERE operation='google' AND request_key=?",
                byte[].class, key);
        assertCredentialBytesEqual(encrypted, stored, "grandfathered encrypted replay");
        assertEquals(seededAccess, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                        crypto.digest(accessToken)).toInstant(),
                "replay must not silently change the seeded legacy access deadline");
        assertEquals(seededRefresh, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                        crypto.digest(refreshToken)).toInstant(),
                "replay must not silently change the seeded legacy refresh deadline");
        assertEquals(seededReplay, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_idempotency WHERE operation='google' AND request_key=?",
                        Timestamp.class, key).toInstant(),
                "replay must not silently change the seeded legacy replay deadline");

        Reply rotated = post("/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, rotated.status, "refresh after grandfathered replay must rotate");
        assertLosslessMillis(parseExpiry("refresh", rotated.node(), "accessExpiresAt"), "rotated accessExpiresAt");
        assertLosslessMillis(parseExpiry("refresh", rotated.node(), "refreshExpiresAt"), "rotated refreshExpiresAt");
        assertEquals(seededAccess, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                        crypto.digest(accessToken)).toInstant(),
                "fresh rotation must not rewrite the grandfathered access deadline");
        assertEquals(seededRefresh, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_credentials WHERE fingerprint=?", Timestamp.class,
                        crypto.digest(refreshToken)).toInstant(),
                "fresh rotation must not rewrite the grandfathered refresh deadline");
        assertEquals(seededReplay, jdbc.queryForObject(
                        "SELECT expires_at FROM auth_idempotency WHERE operation='google' AND request_key=?",
                        Timestamp.class, key).toInstant(),
                "fresh rotation must not rewrite the grandfathered replay deadline");
    }

    @Test
    void cleanupAtOneNanosecondBeforeExpiryRetainsAndAtEqualExpiryRemovesCredentialAndReplay() {
        String[] challenge = freshChallenge();
        when(verifier.verify("cleanup-precision-token", challenge[1]))
                .thenReturn(new Verified("cleanup-precision-subject", null));
        UUID key = UUID.randomUUID();
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"cleanup-precision-token\"}",
                Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        Instant refreshExp = parseExpiry("exchange", grant.node(), "refreshExpiresAt");
        UUID session = sessionOfRefresh(grant.node().path("refreshToken").asText());
        try {
            clock.set(refreshExp.minusNanos(1));
            authService.cleanupExpired();
            assertEquals(1, credentialCount(session),
                    "cleanup before refresh expiry must retain only the current refresh credential");
            assertEquals(refreshExp, jdbc.queryForObject(
                            "SELECT expires_at FROM auth_credentials WHERE session_id=?", Timestamp.class, session)
                            .toInstant(),
                    "cleanup must retain the current refresh deadline");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_idempotency WHERE operation='google' AND request_key=?",
                    Integer.class, key), "cleanup before replay expiry must retain the replay record");

            clock.set(refreshExp);
            authService.cleanupExpired();
            assertEquals(0, credentialCount(session),
                    "cleanup at exact refresh expiry must remove the credentials");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM auth_idempotency WHERE operation='google' AND request_key=?",
                    Integer.class, key), "cleanup at exact replay expiry must remove the replay record");
        } finally {
            clock.set(BASE);
        }
    }

    private void awaitLockWaiters() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity "
                            + "WHERE wait_event_type='Lock' AND datname=current_database()", Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("backend request never blocked on the held lock");
    }

    private ExecutorService[] holdAccountLock(UUID account, CountDownLatch holding, CountDownLatch release) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> {
            new TransactionTemplate(txManager).execute(status -> {
                jdbc.query("SELECT 1 FROM accounts WHERE id=? FOR UPDATE", rs -> null, account);
                holding.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "lock gate release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            return null;
        });
        return new ExecutorService[]{pool};
    }

    @Test
    void refreshExpiringDuringLockWaitIsRejectedWithoutTrace() throws Exception {
        String[] challenge = freshChallenge();
        when(verifier.verify("lockwait-refresh-token", challenge[1]))
                .thenReturn(new Verified("lockwait-refresh-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"lockwait-refresh-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, "exchange failed");
        String refreshToken = grant.node().path("refreshToken").asText();
        UUID account = UUID.fromString(grant.node().path("account").path("id").asText());
        UUID session = sessionOfRefresh(refreshToken);
        int credentialsBefore = credentialCount(session);
        Instant expiry = parseExpiry("exchange", grant.node(), "refreshExpiresAt");

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService[] holder = holdAccountLock(account, holding, release);
        ExecutorService http = Executors.newSingleThreadExecutor();
        try {
            assertTrue(holding.await(15, TimeUnit.SECONDS), "lock holder never acquired the account lock");
            String refreshBody = "{\"refreshToken\":\"" + refreshToken + "\"}";
            Future<Reply> pending = http.submit(() -> post("/v1/auth/refresh", refreshBody,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString())));
            awaitLockWaiters();
            clock.set(expiry);
            release.countDown();
            Reply outcome = pending.get(30, TimeUnit.SECONDS);
            assertEquals(401, outcome.status, "refresh expiring during the lock wait must be rejected");
            assertEquals("reauthentication_required", outcome.node().path("errorCode").asText(),
                    "lock-wait expiry must report reauthentication_required");
            assertEquals(credentialsBefore, credentialCount(session),
                    "rejected lock-wait refresh must not mint credentials");
            assertTrue(jdbc.queryForObject("SELECT consumed_at IS NULL FROM auth_credentials WHERE fingerprint=?",
                    Boolean.class, crypto.digest(refreshToken)),
                    "rejected lock-wait refresh must not consume the credential");
            assertTrue(jdbc.queryForObject("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=?",
                    Boolean.class, session), "expiry alone must not revoke the session family");

            clock.set(BASE);
            Reply retry = post("/v1/auth/refresh", refreshBody,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, retry.status, "refresh must succeed again once the clock is before expiry");
        } finally {
            release.countDown();
            clock.set(BASE);
            holder[0].shutdownNow();
            http.shutdownNow();
        }
    }

    @Test
    void logoutExpiringDuringLockWaitDoesNotRevoke() throws Exception {
        String[] challenge = freshChallenge();
        when(verifier.verify("lockwait-logout-token", challenge[1]))
                .thenReturn(new Verified("lockwait-logout-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"lockwait-logout-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, "exchange failed");
        String refreshToken = grant.node().path("refreshToken").asText();
        String accessToken = grant.node().path("accessToken").asText();
        UUID account = UUID.fromString(grant.node().path("account").path("id").asText());
        UUID session = sessionOfRefresh(refreshToken);
        Instant expiry = parseExpiry("exchange", grant.node(), "refreshExpiresAt");

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService[] holder = holdAccountLock(account, holding, release);
        ExecutorService http = Executors.newSingleThreadExecutor();
        try {
            assertTrue(holding.await(15, TimeUnit.SECONDS), "lock holder never acquired the account lock");
            String logoutBody = "{\"refreshToken\":\"" + refreshToken + "\"}";
            Future<Reply> pending = http.submit(() -> post("/v1/auth/logout", logoutBody, null));
            awaitLockWaiters();
            clock.set(expiry);
            release.countDown();
            Reply outcome = pending.get(30, TimeUnit.SECONDS);
            assertEquals(204, outcome.status, "logout response must stay 204");
            assertTrue(jdbc.queryForObject("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=?",
                    Boolean.class, session),
                    "logout expiring during the lock wait must not revoke the session");
            clock.set(BASE);
            authorization.requireLiveAccessForRead(accessToken);
        } finally {
            release.countDown();
            clock.set(BASE);
            holder[0].shutdownNow();
            http.shutdownNow();
        }
    }

    @Test
    void writeAccessExpiringDuringLockWaitIsRejected() throws Exception {
        String[] challenge = freshChallenge();
        when(verifier.verify("lockwait-access-token", challenge[1]))
                .thenReturn(new Verified("lockwait-access-subject", null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"lockwait-access-token\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.status, "exchange failed");
        String accessToken = grant.node().path("accessToken").asText();
        UUID account = UUID.fromString(grant.node().path("account").path("id").asText());
        Instant expiry = parseExpiry("exchange", grant.node(), "accessExpiresAt");

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService[] holder = holdAccountLock(account, holding, release);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertTrue(holding.await(15, TimeUnit.SECONDS), "lock holder never acquired the account lock");
            Future<UUID> pending = caller.submit(() -> authorization.requireLiveAccess(accessToken));
            awaitLockWaiters();
            clock.set(expiry);
            release.countDown();
            try {
                pending.get(30, TimeUnit.SECONDS);
                throw new AssertionError("write authorization expiring during the lock wait must be rejected");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof AuthService.AuthException,
                        "write authorization expiry must report authentication_required");
            }
        } finally {
            release.countDown();
            clock.set(BASE);
            holder[0].shutdownNow();
            caller.shutdownNow();
        }
    }

    @Test
    void storedEncryptedReplayDecryptsToReturnedGrantBytes() {
        String[] challenge = freshChallenge();
        when(verifier.verify("instance-precision-token", challenge[1]))
                .thenReturn(new Verified("instance-precision-subject", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + challenge[0] + "\",\"idToken\":\"instance-precision-token\"}";
        Reply grant = post("/v1/auth/google", body, Map.of("Idempotency-Key", key.toString()));
        assertEquals(200, grant.status, "exchange failed");
        byte[] stored = jdbc.queryForObject(
                "SELECT encrypted_response FROM auth_idempotency WHERE operation='google' AND request_key=?",
                byte[].class, key);
        assertTrue(stored != null && stored.length > 0, "encrypted replay must be durably stored");
        assertTrue(MessageDigest.isEqual(crypto.decrypt(stored), grant.body.getBytes(StandardCharsets.UTF_8)),
                "decrypted stored replay must equal the returned grant bytes");
    }
}
