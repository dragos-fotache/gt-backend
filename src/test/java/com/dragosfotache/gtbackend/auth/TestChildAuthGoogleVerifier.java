package com.dragosfotache.gtbackend.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test-classpath-only synthetic Google boundary for child service processes in
 * auth precision restart tests.
 *
 * <p>Active only when {@code auth.qa.child-google.enabled} is true. It is a synthetic
 * external boundary for isolated tests, not a production bypass: production never sets
 * this property, and ordinary tests never set it either. Every well-formed token maps
 * to one fixed test subject so child A acceptance and child B recovery share the same
 * identity without live Google.
 */
@Component
@ConditionalOnProperty(name = "auth.qa.child-google.enabled", havingValue = "true")
@Primary
public class TestChildAuthGoogleVerifier extends GoogleTokenVerifier {
    private static final String FIXED_SUBJECT = "auth-precision-child-subject";

    public TestChildAuthGoogleVerifier() {
        super("auth-precision-child-audience", "https://localhost/auth-precision-child-certs");
    }

    @Override
    public Verified verify(String token, String expectedNonce) {
        if (token == null || token.isBlank() || expectedNonce == null || expectedNonce.isBlank()) {
            throw new InvalidGoogleToken();
        }
        return new Verified(FIXED_SUBJECT, null);
    }
}
