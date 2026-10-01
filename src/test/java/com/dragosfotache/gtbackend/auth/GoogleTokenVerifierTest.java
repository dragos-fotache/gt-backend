package com.dragosfotache.gtbackend.auth;

import com.google.api.client.json.gson.GsonFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleTokenVerifierTest {
    @Test
    void verifiesSignatureIssuerAudienceExpiryNonceAndSubjectAgainstControlledPublicKeys() throws Exception {
        var dir = Files.createTempDirectory("google-jwt-test");
        String password = java.util.UUID.randomUUID().toString();
        var ksFile = dir.resolve("signer.p12");
        String keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "unit-key", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Unit Test", "-keystore", ksFile.toString(), "-storetype", "PKCS12", "-storepass", password, "-keypass", password, "-noprompt").redirectErrorStream(true).start();
        assertEquals(0, p.waitFor(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ksFile)) {
            ks.load(in, password.toCharArray());
        }
        var privateKey = (java.security.PrivateKey) ks.getKey("unit-key", password.toCharArray());
        var cert = ks.getCertificate("unit-key");
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            byte[] response = ("{\"test-key\":\"" + pem.replace("\n", "\\n") + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var verifier = new GoogleTokenVerifier("server-client-id", "http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
            String valid = jwt(privateKey, "https://accounts.google.com", "server-client-id", "expected-nonce");
            var result = verifier.verify(valid, "expected-nonce");
            assertEquals("stable-google-subject", result.subject());
            assertThrows(GoogleTokenVerifier.InvalidGoogleToken.class, () -> verifier.verify(jwt(privateKey, "https://attacker.invalid", "server-client-id", "expected-nonce"), "expected-nonce"));
            assertThrows(GoogleTokenVerifier.InvalidGoogleToken.class, () -> verifier.verify(jwt(privateKey, "https://accounts.google.com", "other-client-id", "expected-nonce"), "expected-nonce"));
            assertThrows(GoogleTokenVerifier.InvalidGoogleToken.class, () -> verifier.verify(valid, "different-nonce"));
            int signatureStart = valid.lastIndexOf('.') + 1;
            String badSignature = valid.substring(0, signatureStart) + (valid.charAt(signatureStart) == 'A' ? 'B' : 'A') + valid.substring(signatureStart + 1);
            assertThrows(GoogleTokenVerifier.InvalidGoogleToken.class, () -> verifier.verify(badSignature, "expected-nonce"));
            String expired = jwt(privateKey, "https://accounts.google.com", "server-client-id", "expected-nonce", Instant.now().minusSeconds(60).getEpochSecond());
            assertThrows(GoogleTokenVerifier.InvalidGoogleToken.class, () -> verifier.verify(expired, "expected-nonce"));
        } finally {
            server.stop(0);
        }
    }

    private String jwt(java.security.PrivateKey key, String issuer, String audience, String nonce) throws Exception {
        return jwt(key, issuer, audience, nonce, Instant.now().plusSeconds(120).getEpochSecond());
    }

    private String jwt(java.security.PrivateKey key, String issuer, String audience, String nonce, long expiry) throws Exception {
        var mapper = GsonFactory.getDefaultInstance();
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.toByteArray(mapper.fromString("{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}", com.google.api.client.json.GenericJson.class)));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.toByteArray(mapper.fromString("{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"exp\":" + expiry + ",\"iat\":" + Instant.now().getEpochSecond() + ",\"nonce\":\"" + nonce + "\",\"sub\":\"stable-google-subject\"}", com.google.api.client.json.GenericJson.class)));
        String unsigned = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
}
