package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Versioned, account-bound continuation signing, independent from list and pull token formats. */
@Service
public final class SnapshotPageTokenService {
    private static final byte[] MAGIC = "GTSNAPSHOT".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;
    private static final int ENCODED_TOKEN_CHARS = 132;
    private final byte[] signingKey;
    private final Clock clock;

    public SnapshotPageTokenService(AuthCrypto crypto, Clock clock) {
        this.signingKey = crypto.digest("GymTracka sync snapshot page token signing key v1");
        this.clock = clock;
    }

    public String issue(UUID accountId, UUID snapshotId, long offset, int limit, Instant expiresAt) {
        if (offset < 1 || limit < 1 || limit > 500) throw new IllegalArgumentException("invalid snapshot token fields");
        byte[] payload = encode(accountId, snapshotId, offset, limit, expiresAt);
        byte[] token = Arrays.copyOf(payload, payload.length + MAC_BYTES);
        System.arraycopy(sign(payload), 0, token, payload.length, MAC_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** Authenticity and request context are checked before the typed expiration outcome. */
    public Claims validate(String token, UUID accountId, int limit) {
        if (token == null || token.length() != ENCODED_TOKEN_CHARS) throw new InvalidSnapshotToken();
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token))
                throw new InvalidSnapshotToken();
        } catch (IllegalArgumentException e) {
            throw new InvalidSnapshotToken();
        }
        int payloadLength = MAGIC.length + 1 + 16 + 16 + Long.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES;
        if (decoded.length != payloadLength + MAC_BYTES) throw new InvalidSnapshotToken();
        byte[] payload = Arrays.copyOf(decoded, payloadLength);
        byte[] suppliedMac = Arrays.copyOfRange(decoded, payloadLength, decoded.length);
        if (!MessageDigest.isEqual(sign(payload), suppliedMac)) throw new InvalidSnapshotToken();

        Claims claims = decode(payload);
        if (!claims.accountId().equals(accountId) || claims.limit() != limit)
            throw new InvalidSnapshotToken();
        if (!clock.instant().isBefore(claims.expiresAt())) throw new SnapshotExpired();
        return claims;
    }

    public void requireNotExpired(Instant expiresAt) {
        if (!clock.instant().isBefore(expiresAt)) throw new SnapshotExpired();
    }

    private byte[] encode(UUID accountId, UUID snapshotId, long offset, int limit, Instant expiresAt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeByte(VERSION);
            writeUuid(out, accountId);
            writeUuid(out, snapshotId);
            out.writeLong(offset);
            out.writeInt(limit);
            writeInstant(out, expiresAt);
            out.flush();
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("could not encode snapshot continuation", e);
        }
    }

    private Claims decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC) || in.readUnsignedByte() != VERSION)
                throw new InvalidSnapshotToken();
            UUID accountId = readUuid(in);
            UUID snapshotId = readUuid(in);
            long offset = in.readLong();
            int limit = in.readInt();
            Instant expiresAt = readInstant(in);
            if (offset < 1 || limit < 1 || limit > 500 || in.available() != 0)
                throw new InvalidSnapshotToken();
            return new Claims(accountId, snapshotId, offset, limit, expiresAt);
        } catch (InvalidSnapshotToken e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidSnapshotToken();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign snapshot continuation", e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws Exception {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeInstant(DataOutputStream out, Instant value) throws Exception {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws Exception {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new InvalidSnapshotToken();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    public record Claims(UUID accountId, UUID snapshotId, long offset, int limit, Instant expiresAt) { }

    public static final class InvalidSnapshotToken extends RuntimeException { }
    public static final class SnapshotExpired extends RuntimeException { }
}
