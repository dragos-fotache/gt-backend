package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Shared versioned signing and validation for live resource-list keysets. */
@Service
public final class ListPageTokenService {
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;
    private static final int UUID_BYTES = 16;
    private final byte[] signingKey;
    private final Clock clock;

    public ListPageTokenService(AuthCrypto crypto, Clock clock) {
        this.signingKey = crypto.digest("GymTracka list page signing key v1");
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public Instant firstPageExpiry() {
        return now().plusSeconds(15 * 60);
    }

    public byte[] exerciseContext(String search, List<UUID> muscleIds, List<UUID> equipmentIds) {
        return hashContext(out -> {
            writeUtf8(out, "exercise-search-v1");
            writeUtf8(out, search == null ? "" : search);
            writeUuidSet(out, muscleIds);
            writeUuidSet(out, equipmentIds);
        });
    }

    public byte[] workoutContext(String status) {
        return hashContext(out -> {
            writeUtf8(out, "workout-status-v1");
            writeUtf8(out, status == null ? "" : status);
        });
    }

    public byte[] routineContext() {
        return hashContext(out -> writeUtf8(out, "saved-routines-v1"));
    }

    public Continuation validate(String token, UUID accountId, Endpoint endpoint, byte[] context, int limit) {
        if (token == null || (token.length() != 152 && token.length() != 168))
            throw new InvalidPageTokenException();
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token))
                throw new InvalidPageTokenException();
        } catch (IllegalArgumentException e) {
            throw new InvalidPageTokenException();
        }
        if ((decoded.length != 114 && decoded.length != 126) || decoded.length < MAC_BYTES)
            throw new InvalidPageTokenException();

        int payloadLength = decoded.length - MAC_BYTES;
        byte[] payload = java.util.Arrays.copyOf(decoded, payloadLength);
        byte[] suppliedMac = java.util.Arrays.copyOfRange(decoded, payloadLength, decoded.length);
        if (!MessageDigest.isEqual(sign(payload), suppliedMac)) throw new InvalidPageTokenException();

        TokenFields fields = decodePayload(payload, endpoint);
        boolean contextMatches = MessageDigest.isEqual(fields.contextDigest(), context);
        if (!fields.accountId().equals(accountId) || fields.endpoint() != endpoint || fields.limit() != limit
                || !contextMatches) throw new InvalidPageTokenException();
        if (!now().isBefore(fields.expiresAt())) throw new PageExpiredException();
        return new Continuation(fields.anchor(), fields.expiresAt());
    }

    public String issue(UUID accountId, Endpoint endpoint, byte[] context, int limit, Anchor anchor, Instant expiresAt) {
        byte[] payload = encodePayload(accountId, endpoint, context, limit, anchor, expiresAt);
        byte[] signature = sign(payload);
        byte[] token = java.util.Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, token, payload.length, signature.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private TokenFields decodePayload(byte[] payload, Endpoint expectedEndpoint) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new InvalidPageTokenException();
            UUID account = readUuid(in);
            Endpoint endpoint = Endpoint.fromCode(in.readUnsignedByte());
            if (endpoint != expectedEndpoint) throw new InvalidPageTokenException();
            int expectedPayloadLength = 66 + (endpoint.anchorType == AnchorType.UUID ? UUID_BYTES : 28);
            if (payload.length != expectedPayloadLength) throw new InvalidPageTokenException();
            int limit = in.readInt();
            if (limit < 1 || limit > 200) throw new InvalidPageTokenException();
            byte[] context = in.readNBytes(32);
            if (context.length != 32) throw new InvalidPageTokenException();
            Instant expiresAt = readInstant(in);
            Anchor anchor = readAnchor(in, endpoint);
            if (in.available() != 0) throw new InvalidPageTokenException();
            return new TokenFields(account, endpoint, context, limit, expiresAt, anchor);
        } catch (InvalidPageTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidPageTokenException();
        }
    }

    private byte[] encodePayload(UUID accountId, Endpoint endpoint, byte[] context, int limit, Anchor anchor,
                                 Instant expiresAt) {
        if (context.length != 32 || limit < 1 || limit > 200 || endpoint.anchorType != anchor.type())
            throw new IllegalArgumentException("invalid page token fields");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            writeUuid(out, accountId);
            out.writeByte(endpoint.code);
            out.writeInt(limit);
            out.write(context);
            writeInstant(out, expiresAt);
            writeAnchor(out, anchor);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode page token", e);
        }
    }

    private Anchor readAnchor(DataInputStream in, Endpoint endpoint) throws IOException {
        if (endpoint.anchorType == AnchorType.UUID) return new UuidAnchor(readUuid(in));
        return new WorkoutAnchor(readInstant(in), readUuid(in));
    }

    private static void writeAnchor(DataOutputStream out, Anchor anchor) throws IOException {
        if (anchor instanceof UuidAnchor value) writeUuid(out, value.id());
        else if (anchor instanceof WorkoutAnchor value) {
            writeInstant(out, value.startedAt());
            writeUuid(out, value.id());
        } else throw new IllegalArgumentException("unknown page anchor");
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign page token", e);
        }
    }

    private static byte[] hashContext(ContextWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writer.write(out);
            out.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new IllegalStateException("could not encode page context", e);
        }
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeUuidSet(DataOutputStream out, List<UUID> ids) throws IOException {
        out.writeInt(ids.size());
        for (UUID id : ids) writeUtf8(out, id.toString());
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new InvalidPageTokenException();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    @FunctionalInterface
    private interface ContextWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private record TokenFields(UUID accountId, Endpoint endpoint, byte[] contextDigest, int limit,
                               Instant expiresAt, Anchor anchor) {
    }

    public enum Endpoint {
        EXERCISES(1, AnchorType.UUID), WORKOUTS(2, AnchorType.WORKOUT), ROUTINES(3, AnchorType.UUID);
        private final int code;
        private final AnchorType anchorType;

        Endpoint(int code, AnchorType anchorType) {
            this.code = code;
            this.anchorType = anchorType;
        }

        private static Endpoint fromCode(int code) {
            for (Endpoint value : values()) if (value.code == code) return value;
            throw new InvalidPageTokenException();
        }
    }

    public enum AnchorType { UUID, WORKOUT }

    public sealed interface Anchor permits UuidAnchor, WorkoutAnchor {
        AnchorType type();
    }

    public record UuidAnchor(UUID id) implements Anchor {
        @Override public AnchorType type() { return AnchorType.UUID; }
    }

    public record WorkoutAnchor(Instant startedAt, UUID id) implements Anchor {
        @Override public AnchorType type() { return AnchorType.WORKOUT; }
    }

    public record Continuation(Anchor anchor, Instant expiresAt) {
    }

    public static final class InvalidPageTokenException extends RuntimeException {
    }

    public static final class PageExpiredException extends RuntimeException {
    }
}
