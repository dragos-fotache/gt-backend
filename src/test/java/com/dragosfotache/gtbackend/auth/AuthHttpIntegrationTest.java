package com.dragosfotache.gtbackend.auth;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("auth.google-audience", () -> "integration-audience");
        r.add("auth.encryption-key", () -> key);
    }

    @LocalServerPort
    int port;
    @MockitoBean
    GoogleTokenVerifier verifier;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    AuthService authService;
    @org.springframework.beans.factory.annotation.Autowired
    SessionAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired
    AuthCrypto crypto;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void cleanupErasesExpiredChallengesReplayAndCredentialsButRetainsLiveGrantMaterial() {
        var c = challenge();
        var expiredChallenge = challenge();
        var liveChallenge = challenge();
        when(verifier.verify("retention", c.nonce)).thenReturn(new Verified("subject-retention", null));
        UUID key = UUID.randomUUID();
        var grant = postGrant(key, "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"retention\"}");
        assertEquals(200, grant.getStatusCode().value());
        UUID session = jdbc.queryForObject("SELECT session_id FROM auth_credentials WHERE fingerprint=?", UUID.class, crypto.digest((String) grant.getBody().get("refreshToken")));
        byte[] oldRefresh = crypto.digest((String) grant.getBody().get("refreshToken"));
        byte[] access = crypto.digest((String) grant.getBody().get("accessToken"));
        jdbc.update("UPDATE auth_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", UUID.fromString(expiredChallenge.id));
        jdbc.update("UPDATE auth_credentials SET expires_at=clock_timestamp()-interval '1 second' WHERE fingerprint=?", access);
        jdbc.update("UPDATE auth_idempotency SET expires_at=clock_timestamp()+interval '1 hour' WHERE operation='google' AND request_key=?", key);
        jdbc.update("INSERT INTO auth_sessions(id,account_id,created_at) SELECT ?,account_id,clock_timestamp() FROM auth_sessions WHERE id=?", UUID.randomUUID(), session);
        assertTrue(authService.cleanupExpired() > 0);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_challenges WHERE id=?", Integer.class, UUID.fromString(c.id)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_challenges WHERE id=?", Integer.class, UUID.fromString(expiredChallenge.id)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_challenges WHERE id=?", Integer.class, UUID.fromString(liveChallenge.id)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_credentials WHERE fingerprint=?", Integer.class, oldRefresh));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_credentials WHERE fingerprint=?", Integer.class, access));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_idempotency WHERE operation='google' AND request_key=?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_credentials WHERE session_id=?", Integer.class, session));
        jdbc.update("UPDATE auth_credentials SET expires_at=clock_timestamp()-interval '1 second' WHERE fingerprint=?", oldRefresh);
        jdbc.update("UPDATE auth_idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE operation='google' AND request_key=?", key);
        assertTrue(authService.cleanupExpired() > 0);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_credentials WHERE fingerprint=?", Integer.class, oldRefresh));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_idempotency WHERE operation='google' AND request_key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE id=?", Integer.class, session));
        var expiredRefresh = postRefresh(UUID.randomUUID(), "{\"refreshToken\":\"" + grant.getBody().get("refreshToken") + "\"}");
        assertEquals(401, expiredRefresh.getStatusCode().value());
        assertEquals("reauthentication_required", expiredRefresh.getBody().get("errorCode"));
        var absent = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"retention\"}");
        assertEquals(401, absent.getStatusCode().value());
        var oldAttempt = postGrant(key, "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"retention\"}");
        assertEquals(401, oldAttempt.getStatusCode().value());
        assertEquals("invalid_google_credential", oldAttempt.getBody().get("errorCode"));
    }

    @Test
    void everyAuthErrorResponseIsNoStoreAndDoesNotEchoRequestBodies() {
        String raw = "{not-json secret-token-value";
        var malformed = send(url("/v1/auth/refresh"), raw, headers(), Map.class);
        assertEquals(400, malformed.getStatusCode().value());
        assertEquals("no-store", malformed.getHeaders().getCacheControl());
        assertFalse(String.valueOf(malformed.getBody()).contains("secret-token-value"));
        var missingHeader = send(url("/v1/auth/google"), "{\"idToken\":\"secret-token-value\"}", headers(), Map.class);
        assertEquals(400, missingHeader.getStatusCode().value());
        assertEquals("no-store", missingHeader.getHeaders().getCacheControl());
        assertFalse(String.valueOf(missingHeader.getBody()).contains("secret-token-value"));
    }

    @Test
    void challengePurposeBindingAndProviderFailureDoNotConsumeChallenge() {
        var challenge = send(url("/v1/auth/google/challenge"), "{\"purpose\":\"export\"}", headers(), Map.class);
        assertEquals(200, challenge.getStatusCode().value(), String.valueOf(challenge.getBody()));
        assertEquals("no-store", challenge.getHeaders().getCacheControl());
        String id = (String) challenge.getBody().get("challengeId");
        when(verifier.verify("opaque-test", (String) challenge.getBody().get("nonce"))).thenReturn(new Verified("subject-a", null));
        HttpHeaders h = headers();
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        var wrong = send(url("/v1/auth/google"), "{\"challengeId\":\"" + id + "\",\"idToken\":\"opaque-test\"}", h, Map.class);
        assertEquals(401, wrong.getStatusCode().value());
        assertEquals("invalid_google_credential", wrong.getBody().get("errorCode"));
        var sign = send(url("/v1/auth/google/challenge"), "{}", headers(), Map.class);
        when(verifier.verify("bad", (String) sign.getBody().get("nonce"))).thenThrow(new GoogleTokenVerifier.InvalidGoogleToken());
        HttpHeaders bad = headers();
        bad.set("Idempotency-Key", UUID.randomUUID().toString());
        String wrongNonceBody = "{\"challengeId\":\"" + sign.getBody().get("challengeId") + "\",\"idToken\":\"wrong-nonce\"}";
        when(verifier.verify("wrong-nonce", (String) sign.getBody().get("nonce"))).thenThrow(new GoogleTokenVerifier.InvalidGoogleToken());
        var nonceRejected = send(url("/v1/auth/google"), wrongNonceBody, bad, Map.class);
        assertEquals(401, nonceRejected.getStatusCode().value());
        HttpHeaders badTokenKey = headers();
        badTokenKey.set("Idempotency-Key", UUID.randomUUID().toString());
        String signBody = "{\"challengeId\":\"" + sign.getBody().get("challengeId") + "\",\"idToken\":\"bad\"}";
        var rejected = send(url("/v1/auth/google"), signBody, badTokenKey, Map.class);
        assertEquals(401, rejected.getStatusCode().value());
        when(verifier.verify("valid-after-failure", (String) sign.getBody().get("nonce"))).thenReturn(new Verified("subject-after-failure", null));
        assertEquals(200, postGrant(UUID.randomUUID(), signBody.replace("bad", "valid-after-failure")).getStatusCode().value());
        var expired = challenge();
        jdbc.update("UPDATE auth_challenges SET created_at=clock_timestamp()-interval '5 minutes', expires_at=clock_timestamp() WHERE id=?", UUID.fromString(expired.id));
        when(verifier.verify("expired", expired.nonce)).thenReturn(new Verified("subject-expired", null));
        assertEquals(401, postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + expired.id + "\",\"idToken\":\"expired\"}").getStatusCode().value());
    }

    @Test
    void exchangeReplayExactBodyRefreshRotationReuseAndLogoutAreSessionScoped() {
        UUID key1 = UUID.randomUUID();
        var c1 = challenge();
        when(verifier.verify("google-A", c1.nonce)).thenReturn(new Verified("stable-subject", null));
        String body = "{ \"challengeId\" : \"" + c1.id + "\", \"idToken\" : \"google-A\" }";
        java.time.Instant started = java.time.Instant.now();
        var grant = postGrant(key1, body);
        assertEquals(200, grant.getStatusCode().value(), String.valueOf(grant.getBody()));
        assertEquals("no-store", grant.getHeaders().getCacheControl());
        java.time.Instant issuedAccess = java.time.Instant.parse((String) grant.getBody().get("accessExpiresAt"));
        java.time.Instant issuedRefresh = java.time.Instant.parse((String) grant.getBody().get("refreshExpiresAt"));
        assertFalse(issuedAccess.isBefore(started.plusSeconds(899)));
        assertFalse(issuedAccess.isAfter(java.time.Instant.now().plusSeconds(901)));
        assertFalse(issuedRefresh.isBefore(started.plus(java.time.Duration.ofDays(30)).minusSeconds(1)));
        assertFalse(issuedRefresh.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(30)).plusSeconds(1)));
        var replay = postGrant(key1, body);
        assertEquals(grant.getBody(), replay.getBody());

        var changed = postGrant(key1, body.replace(" : ", ":"));
        assertEquals(409, changed.getStatusCode().value());
        Map<String, Object> session = grant.getBody();
        String refresh = (String) session.get("refreshToken");
        UUID rk = UUID.randomUUID();
        String rb = "{\"refreshToken\":\"" + refresh + "\"}";
        var rotated = postRefresh(rk, rb);
        assertEquals(200, rotated.getStatusCode().value());
        assertEquals(session.get("account"), rotated.getBody().get("account"));
        assertEquals(rotated.getBody(), postRefresh(rk, rb).getBody());
        var reused = postRefresh(UUID.randomUUID(), rb);
        assertEquals(401, reused.getStatusCode().value());
        var c2 = challenge();
        when(verifier.verify("google-B", c2.nonce)).thenReturn(new Verified("stable-subject", null));
        var second = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + c2.id + "\",\"idToken\":\"google-B\"}");
        assertEquals(200, second.getStatusCode().value());
        assertEquals(((Map<?, ?>) session.get("account")).get("id"), ((Map<?, ?>) second.getBody().get("account")).get("id"));
        assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess((String) session.get("accessToken")));
        assertEquals(((Map<?, ?>) second.getBody().get("account")).get("id"), authorization.requireLiveAccess((String) second.getBody().get("accessToken")).toString());
        String secondRefresh = (String) second.getBody().get("refreshToken");
        var secondRotation = postRefresh(UUID.randomUUID(), "{\"refreshToken\":\"" + secondRefresh + "\"}");
        assertEquals(200, secondRotation.getStatusCode().value());
        var logout = send(url("/v1/auth/logout"), "{\"refreshToken\":\"" + secondRefresh + "\"}", headers(), String.class);
        assertEquals(204, logout.getStatusCode().value());
        assertEquals("no-store", logout.getHeaders().getCacheControl());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM auth_sessions s WHERE revoked_at IS NOT NULL AND account_id=(SELECT account_id FROM google_identity_bindings WHERE google_subject='stable-subject')", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT s.revoked_at FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id WHERE c.fingerprint=?", java.sql.Timestamp.class, crypto.digest((String) secondRotation.getBody().get("accessToken"))));
        assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess((String) secondRotation.getBody().get("accessToken")));
        assertEquals(204, send(url("/v1/auth/logout"), "{\"refreshToken\":\"unknown\"}", headers(), String.class).getStatusCode().value());
    }

    @Test
    void encryptedReplaySurvivesReplacementOfTheServiceInstance() {
        var c = challenge();
        when(verifier.verify("instance-replay", c.nonce)).thenReturn(new Verified("subject-instance-replay", null));
        UUID key = UUID.randomUUID();
        String body = "{ \"challengeId\" : \"" + c.id + "\", \"idToken\" : \"instance-replay\" }";
        HttpHeaders h = headers();
        h.set("Idempotency-Key", key.toString());
        String initial = send(url("/v1/auth/google"), body, h, String.class).getBody();
        var replacement = new AuthService(jdbc, crypto, verifier, new com.fasterxml.jackson.databind.ObjectMapper());
        byte[] replayed = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> replacement.exchange(key, UUID.fromString(c.id), "instance-replay", body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertArrayEquals(initial.getBytes(java.nio.charset.StandardCharsets.UTF_8), replayed);
    }

    @Test
    void consumedChallengeRejectsDifferentKeysAndDeletedBindingCannotSignInButRemovedBindingCanRebind() {
        var c = challenge();
        when(verifier.verify("binding-check", c.nonce)).thenReturn(new Verified("subject-binding-check", null));
        String body = "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"binding-check\"}";
        var first = postGrant(UUID.randomUUID(), body);
        assertEquals(200, first.getStatusCode().value());
        var duplicateChallenge = postGrant(UUID.randomUUID(), body);
        assertEquals(401, duplicateChallenge.getStatusCode().value());
        UUID oldAccount = UUID.fromString((String) ((Map<?, ?>) first.getBody().get("account")).get("id"));
        jdbc.update("UPDATE accounts SET deleted_at=clock_timestamp() WHERE id=?", oldAccount);
        var blockedChallenge = challenge();
        when(verifier.verify("deleted-binding", blockedChallenge.nonce)).thenReturn(new Verified("subject-binding-check", null));
        var blocked = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + blockedChallenge.id + "\",\"idToken\":\"deleted-binding\"}");
        assertEquals(401, blocked.getStatusCode().value());
        jdbc.update("DELETE FROM google_identity_bindings WHERE account_id=?", oldAccount);
        var signup = challenge();
        when(verifier.verify("new-signup", signup.nonce)).thenReturn(new Verified("subject-binding-check", null));
        var rebound = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + signup.id + "\",\"idToken\":\"new-signup\"}");
        assertEquals(200, rebound.getStatusCode().value());
        assertNotEquals(oldAccount.toString(), ((Map<?, ?>) rebound.getBody().get("account")).get("id"));
    }

    @Test
    void accessAndRefreshExpiryUseInclusiveExpiryBoundary() {
        var c = challenge();
        when(verifier.verify("expiry-test", c.nonce)).thenReturn(new Verified("subject-expiry", null));
        var grant = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"expiry-test\"}");
        assertEquals(200, grant.getStatusCode().value());
        jdbc.update("UPDATE auth_credentials SET expires_at=clock_timestamp() WHERE fingerprint=?", crypto.digest((String) grant.getBody().get("accessToken")));
        assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess((String) grant.getBody().get("accessToken")));
        jdbc.update("UPDATE auth_credentials SET expires_at=clock_timestamp() WHERE fingerprint=?", crypto.digest((String) grant.getBody().get("refreshToken")));
        var expired = postRefresh(UUID.randomUUID(), "{\"refreshToken\":\"" + grant.getBody().get("refreshToken") + "\"}");
        assertEquals(401, expired.getStatusCode().value());
        assertEquals("reauthentication_required", expired.getBody().get("errorCode"));
    }

    @Test
    void concurrentDifferentKeysCannotBothConsumeOneChallenge() {
        var c = challenge();
        when(verifier.verify("race-challenge", c.nonce)).thenReturn(new Verified("subject-race-challenge", null));
        String body = "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"race-challenge\"}";
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> postGrant(UUID.randomUUID(), body));
            var b = pool.submit(() -> postGrant(UUID.randomUUID(), body));
            var r1 = a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var r2 = b.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, List.of(r1, r2).stream().filter(r -> r.getStatusCode().value() == 200).count());
            assertEquals(1, List.of(r1, r2).stream().filter(r -> r.getStatusCode().value() == 401).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_sessions s JOIN google_identity_bindings g ON g.account_id=s.account_id WHERE g.google_subject='subject-race-challenge'", Integer.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void refreshLogoutRaceAlwaysLeavesSessionRevokedWithoutDeadlock() {
        var c = challenge();
        when(verifier.verify("race-logout", c.nonce)).thenReturn(new Verified("subject-race-logout", null));
        var grant = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"race-logout\"}");
        String oldRefresh = (String) grant.getBody().get("refreshToken");
        UUID refreshKey = UUID.randomUUID();
        String refreshBody = "{\"refreshToken\":\"" + oldRefresh + "\"}";
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var refresh = pool.submit(() -> postRefresh(refreshKey, refreshBody));
            var logout = pool.submit(() -> send(url("/v1/auth/logout"), refreshBody, headers(), String.class));
            var rr = refresh.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var lr = logout.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(rr.getStatusCode().value() == 200 || rr.getStatusCode().value() == 401);
            assertEquals(204, lr.getStatusCode().value());
            assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess((String) grant.getBody().get("accessToken")));
            if (rr.getStatusCode().value() == 200)
                assertThrows(AuthService.AuthException.class, () -> authorization.requireLiveAccess((String) rr.getBody().get("accessToken")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void concurrentSameExchangeKeyFencesToOneDurableGrant() {
        var c = challenge();
        when(verifier.verify("concurrent", c.nonce)).thenReturn(new Verified("subject-concurrent", null));
        UUID key = UUID.randomUUID();
        String body = "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"concurrent\"}";
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> postGrant(key, body));
            var b = pool.submit(() -> postGrant(key, body));
            var r1 = a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var r2 = b.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, r1.getStatusCode().value());
            assertEquals(r1.getBody(), r2.getBody());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_sessions s JOIN accounts a ON a.id=s.account_id JOIN google_identity_bindings g ON g.account_id=a.id WHERE g.google_subject='subject-concurrent'", Integer.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void syncPushHttpRequiresAuthAndPreflightsEntireRequestWithoutWrites() {
        var c = challenge();
        when(verifier.verify("push-auth", c.nonce)).thenReturn(new Verified("subject-push-auth", null));
        var grant = postGrant(UUID.randomUUID(), "{\"challengeId\":\"" + c.id + "\",\"idToken\":\"push-auth\"}");
        String token = (String) grant.getBody().get("accessToken");
        assertEquals(401, push(null, "{\"mutations\":[]}").getStatusCode().value());
        var unauthEmpty = pushNoBody(null);
        assertEquals(401, unauthEmpty.getStatusCode().value());
        assertEquals("authentication_required", unauthEmpty.getBody().get("errorCode"));
        var authEmpty = pushNoBody(token);
        assertEquals(400, authEmpty.getStatusCode().value());
        assertEquals("invalid_request", authEmpty.getBody().get("errorCode"));
        var empty = push(token, "{\"mutations\":[]}");
        assertEquals(200, empty.getStatusCode().value());
        assertEquals(List.of(), empty.getBody().get("results"));
        String valid = "{\"mutationId\":\"00000000-0000-0000-0000-000000000001\",\"type\":\"aggregate.delete\",\"aggregateType\":\"workout\",\"aggregateId\":\"00000000-0000-0000-0000-000000000002\",\"localGeneration\":1,\"payload\":{}}";
        int roots = jdbc.queryForObject("select count(*) from workouts", Integer.class), processed = jdbc.queryForObject("select count(*) from processed_mutations", Integer.class);
        var unavailable = push(token, "{\"mutations\":[" + valid + "]}");
        assertEquals(200, unavailable.getStatusCode().value());
        assertEquals("rejected", ((Map<?, ?>) ((List<?>) unavailable.getBody().get("results")).get(0)).get("status"));
        assertEquals("not_found", ((Map<?, ?>) ((List<?>) unavailable.getBody().get("results")).get(0)).get("errorCode"));
        assertEquals(roots, jdbc.queryForObject("select count(*) from workouts", Integer.class));
        assertEquals(processed + 1, jdbc.queryForObject("select count(*) from processed_mutations", Integer.class));
        String invalid = valid.replace("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000004").replace("\"payload\":{}", "\"payload\":{\"nestedUnknown\":true}");
        var rejected = push(token, "{\"mutations\":[" + valid + "," + invalid + "]}");
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("validation_error", rejected.getBody().get("errorCode"));
        String missingNested = valid.replace(",\"payload\":{}", "");
        var missing = push(token, "{\"mutations\":[" + missingNested + "]}");
        assertEquals(422, missing.getStatusCode().value());
        assertEquals("validation_error", missing.getBody().get("errorCode"));
        String duplicateNested = valid.replace("\"payload\":{}", "\"payload\":{},\"payload\":{}");
        var malformed = push(token, "{bad");
        assertEquals(400, malformed.getStatusCode().value());
        var trailing = push(token, "{\"mutations\":[]} garbage");
        assertEquals(400, trailing.getStatusCode().value());
        var nestedDuplicate = push(token, "{\"mutations\":[" + duplicateNested + "]}");
        assertEquals(400, nestedDuplicate.getStatusCode().value());
        assertEquals(roots, jdbc.queryForObject("select count(*) from workouts", Integer.class));
        assertEquals(processed + 1, jdbc.queryForObject("select count(*) from processed_mutations", Integer.class));
    }

    private ResponseEntity<Map> push(String token, String body) {
        return pushRequest(token, java.net.http.HttpRequest.BodyPublishers.ofString(body));
    }

    private ResponseEntity<Map> pushNoBody(String token) {
        return pushRequest(token, java.net.http.HttpRequest.BodyPublishers.noBody());
    }

    private ResponseEntity<Map> pushRequest(String token, java.net.http.HttpRequest.BodyPublisher publisher) {
        try {
            var q = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url("/v1/sync/push"))).header("Content-Type", "application/json");
            if (token != null) q.header("Authorization", "Bearer " + token);
            var response = java.net.http.HttpClient.newHttpClient().send(q.POST(publisher).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            Map parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
            return new ResponseEntity<>(parsed, HttpStatusCode.valueOf(response.statusCode()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record Challenge(String id, String nonce) {
    }

    private Challenge challenge() {
        var r = send(url("/v1/auth/google/challenge"), "{}", headers(), Map.class);
        return new Challenge((String) r.getBody().get("challengeId"), (String) r.getBody().get("nonce"));
    }

    private ResponseEntity<Map> postGrant(UUID k, String b) {
        HttpHeaders h = headers();
        h.set("Idempotency-Key", k.toString());
        return send(url("/v1/auth/google"), b, h, Map.class);
    }

    private ResponseEntity<Map> postRefresh(UUID k, String b) {
        HttpHeaders h = headers();
        h.set("Idempotency-Key", k.toString());
        return send(url("/v1/auth/refresh"), b, h, Map.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> send(String uri, String body, HttpHeaders headers, Class<T> type) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri)).header("Content-Type", "application/json").headers(headers.getFirst("Idempotency-Key") == null ? new String[0] : new String[]{"Idempotency-Key", headers.getFirst("Idempotency-Key")}).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            T parsed = type == String.class ? (T) response.body() : type == Map.class ? (T) new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class) : null;
            HttpHeaders out = new HttpHeaders();
            response.headers().map().forEach((k, v) -> out.put(k, v));
            return new ResponseEntity<>(parsed, out, HttpStatusCode.valueOf(response.statusCode()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
