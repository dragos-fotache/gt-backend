package com.dragosfotache.gtbackend.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent ring parsing and digest framing coverage.
 *
 * <p>Expected HMAC bytes are computed with independent JCA code from hand-built frames
 * following the normative field order, labels, canonical UUID text and big-endian
 * length prefixes. Production crypto output is compared to those independent vectors
 * for subject, request, version, account, key and body changes. Absent ring is allowed
 * startup capability; a required key use then fails closed.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountDeletionCryptoRingTest {
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "crypto-ring-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("account.deletion.ledger-keys",
                () -> "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}");
    }

    @Autowired
    DeletionLedgerKeys ring;
    @Autowired
    DeletionLedgerCrypto crypto;

    private static byte[] framed(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + raw.length).putInt(raw.length).put(raw).array();
    }

    private static byte[] framed(byte[] raw) {
        return ByteBuffer.allocate(4 + raw.length).putInt(raw.length).put(raw).array();
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] independentHmac(byte[] key, byte[] framed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(framed);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void absentRingIsAllowedButRequiredKeyUseFailsClosed() {
        DeletionLedgerKeys absent = new DeletionLedgerKeys("");
        assertFalse(absent.isConfigured());
        assertThrows(IllegalStateException.class, absent::activeVersion);
        assertThrows(IllegalStateException.class, () -> absent.keyForVersion(1));

        assertTrue(ring.isConfigured());
        assertEquals(1, ring.activeVersion());
        assertEquals(32, ring.keyForVersion(1).length);
    }

    @Test
    void ringAcceptsCanonicalVersionsAndRejectsDuplicatesTrailingUnknownAndBounds() {
        assertThrows(IllegalStateException.class,
                () -> new DeletionLedgerKeys("{\"activeVersion\":\"1\",\"keys\":{}}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"01\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}trailing"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"2\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAA\"}}"));
        assertThrows(IllegalStateException.class,
                () -> new DeletionLedgerKeys("{\"activeVersion\":\"1\"}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"},\"extra\":1}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"0\",\"keys\":{\"0\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}"));
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"32768\",\"keys\":{\"32768\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}"));
        // Duplicate JSON keys collapse in a tree parser; strict parsing must reject them
        // rather than silently keep the last value.
        assertThrows(IllegalStateException.class, () -> new DeletionLedgerKeys(
                "{\"activeVersion\":\"1\",\"keys\":{\"1\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"},"
                        + "\"activeVersion\":\"1\"}"));
    }

    @Test
    void persistenceFaultBoundariesClassifyRetryableVersusUnexpected() {
        assertTrue(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new org.springframework.transaction.CannotCreateTransactionException("acquire")));
        assertTrue(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new org.springframework.transaction.TransactionTimedOutException("timeout")));
        assertFalse(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new org.springframework.transaction.TransactionSystemException("system",
                        new org.springframework.transaction.InvalidIsolationLevelException("isolation"))));
        assertFalse(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new org.springframework.transaction.UnexpectedRollbackException("rollback",
                        new java.sql.SQLTransientConnectionException("transient"))));
        assertFalse(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new org.springframework.transaction.IllegalTransactionStateException("illegal",
                        new java.sql.SQLTransientConnectionException("transient"))));
        assertTrue(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new java.sql.SQLException("connection", "08006")));
        assertTrue(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new java.sql.SQLException("deadlock", "40001")));
        assertFalse(com.dragosfotache.gtbackend.account.LifecycleGoogle.isRetryablePersistenceFault(
                new IllegalArgumentException("unrelated")));
    }

    @Test
    void subjectAndRequestDigestsMatchIndependentJcaVectors() {
        byte[] key = new byte[32];
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        UUID account = UUID.fromString("b5827485-c9d6-49aa-9578-7a2f637cb8bc");
        UUID keyId = UUID.fromString("c5827485-c9d6-49aa-9578-7a2f637cb8bd");
        byte[] body = "{\"confirm\":true}".getBytes(StandardCharsets.UTF_8);

        byte[] subjectFrame = concat(framed("GT-DELETION-SUBJECT-V1"),
                ByteBuffer.allocate(4).putInt(1).array(), framed("google"), framed("subject-alpha"));
        assertArrayEquals(independentHmac(key, subjectFrame), crypto.subjectDigest(key, 1, "subject-alpha"));

        byte[] requestFrame = concat(framed("GT-DELETION-REQUEST-V1"),
                ByteBuffer.allocate(4).putInt(1).array(), framed(account.toString()),
                framed("deletion"), framed(keyId.toString()), framed(body));
        assertArrayEquals(independentHmac(key, requestFrame),
                crypto.requestDigest(key, 1, account, keyId, body));

        // Version, account, key, body and key-material changes all change the digest.
        assertFalse(crypto.constantTimeEquals(crypto.subjectDigest(key, 1, "subject-alpha"),
                crypto.subjectDigest(key, 2, "subject-alpha")));
        assertFalse(crypto.constantTimeEquals(
                crypto.requestDigest(key, 1, account, keyId, body),
                crypto.requestDigest(key, 1, UUID.randomUUID(), keyId, body)));
        assertFalse(crypto.constantTimeEquals(
                crypto.requestDigest(key, 1, account, keyId, body),
                crypto.requestDigest(key, 1, account, UUID.randomUUID(), body)));
        assertFalse(crypto.constantTimeEquals(
                crypto.requestDigest(key, 1, account, keyId, body),
                crypto.requestDigest(key, 1, account, keyId, "{\"confirm\": true}".getBytes(StandardCharsets.UTF_8))));
        assertFalse(crypto.constantTimeEquals(crypto.subjectDigest(key, 1, "subject-alpha"),
                crypto.subjectDigest(otherKey, 1, "subject-alpha")));
        assertFalse(crypto.constantTimeEquals(
                crypto.subjectDigest(key, 1, "subject-alpha"),
                crypto.requestDigest(key, 1, account, keyId, body)));

        assertThrows(IllegalArgumentException.class, () -> crypto.subjectDigest(new byte[16], 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> crypto.subjectDigest(key, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> crypto.subjectDigest(key, 32768, "x"));
        assertThrows(IllegalArgumentException.class, () -> crypto.requestDigest(key, 1, null, keyId, body));
    }
}
