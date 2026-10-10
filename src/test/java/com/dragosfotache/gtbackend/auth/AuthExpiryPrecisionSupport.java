package com.dragosfotache.gtbackend.auth;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared sanitized assertions for auth expiry-precision regression tests.
 *
 * <p>Never includes credential-bearing material (tokens, nonces, request bodies,
 * encrypted payloads) in assertion messages. Credential-bearing bytes are compared
 * with boolean equality only, so even a failure emits no secret material.
 * Timestamps are not credentials and may appear in messages where useful.
 */
final class AuthExpiryPrecisionSupport {
    static final long CHALLENGE_LIFETIME_SECONDS = 300;
    static final long ACCESS_LIFETIME_SECONDS = 900;
    static final long REFRESH_LIFETIME_DAYS = 30;

    private AuthExpiryPrecisionSupport() {
    }

    static Instant truncated(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    static boolean isLosslessMillis(Instant instant) {
        return instant.getNano() % 1_000_000 == 0;
    }

    static void assertLosslessMillis(Instant instant, String context) {
        assertTrue(isLosslessMillis(instant),
                context + " must be losslessly millisecond-representable but was " + instant);
    }

    static void assertCredentialBytesEqual(byte[] expected, byte[] actual, String context) {
        assertTrue(expected != null && actual != null && MessageDigest.isEqual(expected, actual),
                context + ": credential-bearing bytes differ");
    }

    static Instant parseExpiry(String context, JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertTrue(value.isTextual(), context + ": response field " + field + " must be a string");
        String raw = value.asText();
        assertTrue(!raw.isBlank(), context + ": response is missing " + field);
        assertTrue(raw.endsWith("Z"), context + ": response field " + field
                + " must be a UTC RFC3339 instant ending in Z, not an offset form");
        final Instant parsed;
        try {
            parsed = Instant.parse(raw);
        } catch (Exception e) {
            throw new AssertionError(context + ": response field " + field + " is not a valid instant");
        }
        long epochMillis = parsed.toEpochMilli();
        assertTrue(epochMillis >= -62167219200000L && epochMillis <= 253402300799999L,
                context + ": response field " + field + " is outside the supported epoch-millisecond range");
        return parsed;
    }
}
