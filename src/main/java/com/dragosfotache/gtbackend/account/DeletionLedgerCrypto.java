package com.dragosfotache.gtbackend.account;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Domain-separated HMAC-SHA256 digests for deletion evidence.
 * Every string/binary component, including fixed labels, is UTF-8 bytes
 * prefixed by its four-byte big-endian byte length. The numeric version is a
 * raw four-byte big-endian positive integer. Field order and UUID text are
 * pinned; comparisons are constant-time over 32 bytes.
 */
@Component
public class DeletionLedgerCrypto {
    private static final String SUBJECT_LABEL = "GT-DELETION-SUBJECT-V1";
    private static final String REQUEST_LABEL = "GT-DELETION-REQUEST-V1";
    private static final String PROVIDER = "google";
    private static final String OPERATION = "deletion";

    public byte[] subjectDigest(byte[] key, int version, String subject) {
        if (key == null || key.length != 32) throw new IllegalArgumentException();
        checkVersion(version);
        if (subject == null || subject.isEmpty()) throw new IllegalArgumentException();
        byte[] framed = frame(
                framedBytes(SUBJECT_LABEL),
                rawVersion(version),
                framedBytes(PROVIDER),
                framedBytes(subject));
        return hmac(key, framed);
    }

    public byte[] requestDigest(byte[] key, int version, UUID accountId, UUID keyId, byte[] exactBody) {
        if (key == null || key.length != 32) throw new IllegalArgumentException();
        checkVersion(version);
        if (accountId == null || keyId == null || exactBody == null) throw new IllegalArgumentException();
        byte[] framed = frame(
                framedBytes(REQUEST_LABEL),
                rawVersion(version),
                framedBytes(accountId.toString()),
                framedBytes(OPERATION),
                framedBytes(keyId.toString()),
                framedBytes(exactBody));
        return hmac(key, framed);
    }

    public boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != 32 || right.length != 32) return false;
        return MessageDigest.isEqual(left, right);
    }

    private static void checkVersion(int version) {
        if (version < 1 || version > 32767) throw new IllegalArgumentException();
    }

    private static byte[] framedBytes(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        return framedBytes(raw);
    }

    private static byte[] framedBytes(byte[] raw) {
        ByteBuffer prefix = ByteBuffer.allocate(4);
        prefix.putInt(raw.length);
        byte[] framed = new byte[4 + raw.length];
        System.arraycopy(prefix.array(), 0, framed, 0, 4);
        System.arraycopy(raw, 0, framed, 4, raw.length);
        return framed;
    }

    private static byte[] rawVersion(int version) {
        return ByteBuffer.allocate(4).putInt(version).array();
    }

    private static byte[] frame(byte[]... parts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] part : parts) out.write(part);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] framed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(framed);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
