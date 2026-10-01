package com.dragosfotache.gtbackend.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F9a.9 Google boundary regressions using the ACTUAL {@code GoogleTokenVerifier}
 * (no verifier mocking) against controlled stub certificates over real HTTP.
 *
 * <p>Malformed token shapes must fail closed as {@code 401 invalid_google_credential}
 * without consuming the challenge. A provider certificate outage must surface as
 * sanitized {@code 503 temporarily_unavailable} without consuming the challenge, and
 * the exact same proof must succeed once certificates recover. Expected statuses
 * derive from the finalized contract, never from observed output.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportGoogleBoundaryHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "export-google-boundary-audience";

    private static final HttpServer CERTS;
    private static final int CERTS_PORT;
    private static volatile String certsJson;
    private static volatile boolean failCerts;
    private static PrivateKey signingKey;

    static {
        try {
            CERTS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CERTS_PORT = CERTS.getAddress().getPort();
            CERTS.createContext("/certs", exchange -> {
                if (failCerts || certsJson == null) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                byte[] body = certsJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            CERTS.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("auth.google-certificates-url", () -> "http://127.0.0.1:" + CERTS_PORT + "/certs");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void issueStubKeys() throws Exception {
        Path dir = Files.createTempDirectory("task9a-boundary-keys-");
        Path ksFile = dir.resolve("signer.p12");
        String password = UUID.randomUUID().toString();
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process keytoolProcess = new ProcessBuilder(keytool, "-genkeypair", "-alias", "boundary-key",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Boundary Test",
                "-keystore", ksFile.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true).start();
        assertEquals(0, keytoolProcess.waitFor(),
                new String(keytoolProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ksFile)) {
            store.load(in, password.toCharArray());
        }
        signingKey = (PrivateKey) store.getKey("boundary-key", password.toCharArray());
        var certificate = store.getCertificate("boundary-key");
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        certsJson = JSON.writeValueAsString(Map.of("test-key", pem));
    }

    @AfterAll
    static void stopStubCertificates() {
        CERTS.stop(0);
    }

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
    private record Reply(int status, String body, Map<String, List<String>> headers) {
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

    private Reply post(String path, String body, String key) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private String mintToken(String nonce, String subject) {
        try {
            long now = Instant.now().getEpochSecond();
            String header = base64Url("{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}");
            String payload = base64Url("{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + AUDIENCE + "\","
                    + "\"exp\":" + (now + 600) + ",\"iat\":" + (now - 10) + ","
                    + "\"nonce\":\"" + nonce + "\",\"sub\":\"" + subject + "\"}");
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(signingKey);
            signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            return header + "." + payload + "." + signature;
        } catch (Exception e) {
            throw new AssertionError("Could not mint stub Google token", e);
        }
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    private BoundAccount provision(String subject) {
        Reply challenge = post("/v1/auth/google/challenge", "{}", UUID.randomUUID().toString());
        assertEquals(200, challenge.status, challenge.body);
        String token = mintToken(challenge.node().path("nonce").asText(), subject);
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}",
                UUID.randomUUID().toString());
        assertEquals(200, grant.status, grant.body);
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject,
                grant.node().path("accessToken").asText());
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    @Test
    void malformedTokenShapesAreInvalidCredentialsWithoutConsumption() {
        BoundAccount account = provision("malformed-shape-subject");
        String notJson = base64Url("not-json");
        List<String> shapes = List.of(
                "not-a-jwt",
                "aaa.bbb",
                "aaa.bbb.ccc.ddd",
                "!!!.e30.e30",
                "e30.!!!.e30",
                "e30.e30.!!!",
                notJson + ".e30.e30",
                "e30." + notJson + ".e30");
        for (String shape : shapes) {
            Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
            assertEquals(200, challenge.status, challenge.body);
            String challengeId = challenge.node().path("challengeId").asText();
            Reply reply = post("/v1/account/export",
                    "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId
                            + "\",\"idToken\":\"" + shape + "\"}",
                    null);
            assertEquals(401, reply.status, "shape " + shape + ": " + reply.body);
            assertEquals("invalid_google_credential", reply.node().path("errorCode").asText());
            assertEquals("no-store", reply.cacheControl());
            assertFalse(reply.body.contains(shape), "error must not echo the credential");
            assertFalse(challengeConsumed(challengeId), "malformed proof must not consume the challenge");
        }
    }

    @Test
    void providerCertificateOutageIsUnavailableWithoutConsumptionThenSameProofSucceeds() {
        BoundAccount account = provision("outage-subject");
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        assertEquals(200, proof.status, proof.body);
        String challengeId = proof.node().path("challengeId").asText();
        String token = mintToken(proof.node().path("nonce").asText(), "outage-subject");
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId
                + "\",\"idToken\":\"" + token + "\"}";
        failCerts = true;
        try {
            Reply unavailable = post("/v1/account/export", body, null);
            assertEquals(503, unavailable.status, unavailable.body);
            assertEquals("temporarily_unavailable", unavailable.node().path("errorCode").asText());
            assertEquals("no-store", unavailable.cacheControl());
            assertFalse(unavailable.body.contains(token), "error must not echo the credential");
            assertFalse(challengeConsumed(challengeId), "provider outage must not consume the challenge");
        } finally {
            failCerts = false;
        }
        Reply recovered = post("/v1/account/export", body, null);
        assertEquals(200, recovered.status, recovered.body);
        assertEquals(1, recovered.node().path("exportVersion").asInt());
        assertEquals(account.accountId.toString(), recovered.node().path("accountId").asText());
        assertTrue(challengeConsumed(challengeId));
    }

    @Test
    void healthyStubCertificatesAdmitACompleteExport() {
        BoundAccount account = provision("healthy-stub-subject");
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null);
        String token = mintToken(proof.node().path("nonce").asText(), "healthy-stub-subject");
        Reply reply = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                        + proof.node().path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}",
                null);
        assertEquals(200, reply.status, reply.body);
        assertEquals("gymtracka-catalogue-v1", reply.node().path("catalogueVersion").asText());
        assertTrue(reply.node().path("customExercises").isArray());
        assertTrue(reply.node().path("routines").isArray());
        assertTrue(reply.node().path("workouts").isArray());
    }
}
