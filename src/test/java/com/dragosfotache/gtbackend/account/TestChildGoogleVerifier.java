package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test-classpath-only synthetic Google boundary for child service processes.
 *
 * <p>Active only when {@code task9b.child-google.enabled} is true. It is a synthetic
 * external boundary for isolated tests, not a production bypass: production never sets
 * this property. Every token maps to one fixed test subject so child A acceptance and
 * child B recovery share the same identity without live Google.
 */
@Component
@ConditionalOnProperty(name = "task9b.child-google.enabled", havingValue = "true")
@Primary
public class TestChildGoogleVerifier extends GoogleTokenVerifier {
    private static final String FIXED_SUBJECT = "child-a-subject";

    public TestChildGoogleVerifier() {
        super("child-test-audience", "https://localhost/child-certs");
    }

    @Override
    public Verified verify(String token, String expectedNonce) {
        if (token == null || token.isBlank() || expectedNonce == null || expectedNonce.isBlank()) {
            throw new InvalidGoogleToken();
        }
        return new Verified(FIXED_SUBJECT, null);
    }
}
