package com.dragosfotache.gtbackend.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Verifies Google JWT signatures against Google's rotating public keys and all security claims.
 */
@Component
public class GoogleTokenVerifier {
    private final String audience;
    private final String certificatesUrl;

    public GoogleTokenVerifier(@Value("${auth.google-audience:}") String audience,
                               @Value("${auth.google-certificates-url:https://www.googleapis.com/oauth2/v1/certs}") String certificatesUrl) {
        if (audience == null || audience.isBlank())
            throw new IllegalStateException("Google audience is not configured");
        this.audience = audience;
        this.certificatesUrl = certificatesUrl;
    }

    public Verified verify(String token, String expectedNonce) {
        if (audience == null || audience.isBlank())
            throw new IllegalStateException("Google verification is not configured");
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setPublicCertsEncodedUrl(certificatesUrl).setAudience(List.of(audience)).build();
            GoogleIdToken parsed = verifier.verify(token);
            if (parsed == null) throw new InvalidGoogleToken();
            GoogleIdToken.Payload p = parsed.getPayload();
            String issuer = p.getIssuer();
            if (!("accounts.google.com".equals(issuer) || "https://accounts.google.com".equals(issuer)) ||
                    p.getExpirationTimeSeconds() == null || p.getExpirationTimeSeconds() * 1000L <= System.currentTimeMillis() ||
                    !expectedNonce.equals(p.get("nonce")) || p.getSubject() == null || p.getSubject().isBlank())
                throw new InvalidGoogleToken();
            return new Verified(p.getSubject(), p.get("name") instanceof String n ? n : null);
        } catch (IOException | GeneralSecurityException e) {
            throw new InvalidGoogleToken();
        }
    }

    public record Verified(String subject, String displayName) {
    }

    public static final class InvalidGoogleToken extends RuntimeException {
    }
}
