package com.dragosfotache.gtbackend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final AuthCrypto crypto;
    private final GoogleTokenVerifier google;
    private final ObjectMapper mapper;
    private final com.dragosfotache.gtbackend.account.DeletionSubjectFence subjectFence;
    private final Clock clock;

    public AuthService(JdbcTemplate jdbc, AuthCrypto crypto, GoogleTokenVerifier google, ObjectMapper mapper,
                       com.dragosfotache.gtbackend.account.DeletionSubjectFence subjectFence, Clock clock) {
        if (subjectFence == null) throw new IllegalArgumentException("Deletion subject fence is required");
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.google = google;
        this.mapper = mapper;
        this.subjectFence = subjectFence;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> challenge(String purpose) {
        if (purpose == null) purpose = "signIn";
        if (!Set.of("signIn", "export", "deleteAccount", "accountStatus").contains(purpose))
            throw new AuthException(422, "validation_error");
        UUID id = UUID.randomUUID();
        String nonce = crypto.nonce();
        Instant issued = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expires = issued.plusSeconds(300);
        jdbc.update("INSERT INTO auth_challenges(id,nonce,purpose,created_at,expires_at) VALUES (?,?,?::auth_challenge_purpose,?,?)", id, nonce, purpose, Timestamp.from(issued), Timestamp.from(expires));
        return Map.of("challengeId", id, "nonce", nonce, "expiresAt", expires.toString());
    }

    @Transactional
    public byte[] exchange(UUID key, UUID challengeId, String token, byte[] exactBody) {
        byte[] identity = crypto.digest(exactBody);
        byte[] tokenFp = crypto.digest(token);
        serializeKey("google", key);
        byte[] replay = replay("google", key, identity, tokenFp);
        if (replay != null) return replay;
        Challenge c = challengeRow(challengeId);
        if (c == null || c.consumed || !c.purpose.equals("signIn") || !clock.instant().isBefore(c.expires))
            throw new AuthException(401, "invalid_google_credential");
        GoogleTokenVerifier.Verified verified;
        try {
            verified = google.verify(token, c.nonce);
        } catch (RuntimeException e) {
            throw new AuthException(401, "invalid_google_credential");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null, verified.subject());
        subjectFence.checkNewExchangeAllowed(verified.subject(), challengeCreatedAt(challengeId));
        UUID account = jdbc.query("SELECT account_id FROM google_identity_bindings WHERE google_subject=?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, verified.subject());
        if (account == null) {
            account = UUID.randomUUID();
            jdbc.update("INSERT INTO accounts(id) VALUES (?)", account);
            jdbc.update("INSERT INTO google_identity_bindings(google_subject,account_id) VALUES (?,?)", verified.subject(), account);
        }
        if (!lockLiveAccount(account)) throw new AuthException(401, "invalid_google_credential");
        Grant grant = createGrant(account, verified.displayName(), null);
        if (jdbc.update("UPDATE auth_challenges SET consumed_at=clock_timestamp() WHERE id=? AND consumed_at IS NULL", challengeId) != 1)
            throw new AuthException(401, "invalid_google_credential");
        storeReplay("google", key, identity, tokenFp, grant.body, grant.session, grantRefreshExpiry(grant.body));
        return grant.body;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public byte[] refresh(UUID key, String token, byte[] exactBody) {
        byte[] identity = crypto.digest(exactBody), fp = crypto.digest(token);
        serializeKey("refresh", key);
        byte[] replay = replay("refresh", key, identity, fp);
        if (replay != null) return replay;
        Credential c = credential(fp);
        if (c == null || !c.kind.equals("refresh") || c.expires == null || !clock.instant().isBefore(c.expires))
            throw new AuthException(401, "reauthentication_required");
        lockSession(c.session);
        c = credential(fp);
        if (c == null) throw new AuthException(401, "reauthentication_required");
        if (c.expires == null || !clock.instant().isBefore(c.expires))
            throw new AuthException(401, "reauthentication_required");
        if (c.consumed || c.revoked || !accountLive(c.account)) {
            jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE id=?", c.session);
            throw new AuthException(401, "reauthentication_required");
        }
        jdbc.update("UPDATE auth_credentials SET consumed_at=clock_timestamp() WHERE fingerprint=?", fp);
        Grant grant = createGrant(c.account, null, c.session);
        storeReplay("refresh", key, identity, fp, grant.body, grant.session, grantRefreshExpiry(grant.body));
        return grant.body;
    }

    /**
     * Retryable bounded cleanup. Repeat until it returns zero to drain larger backlogs.
     */
    @Transactional
    public int cleanupExpired() {
        // Floor to DB precision so the cutoff never rounds upward and deletes an unexpired row.
        Timestamp cutoff = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        int removed = 0;
        removed += jdbc.update("WITH victims AS (SELECT id FROM auth_challenges WHERE consumed_at IS NOT NULL OR expires_at<=? ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) DELETE FROM auth_challenges c USING victims v WHERE c.id=v.id", cutoff);
        removed += jdbc.update("WITH victims AS (SELECT operation,request_key FROM auth_idempotency WHERE expires_at<=? ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) DELETE FROM auth_idempotency i USING victims v WHERE i.operation=v.operation AND i.request_key=v.request_key", cutoff);
        removed += jdbc.update("WITH victims AS (SELECT fingerprint FROM auth_credentials WHERE expires_at<=? ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) DELETE FROM auth_credentials c USING victims v WHERE c.fingerprint=v.fingerprint", cutoff);
        removed += jdbc.update("WITH victims AS (SELECT s.id FROM auth_sessions s WHERE NOT EXISTS (SELECT 1 FROM auth_credentials c WHERE c.session_id=s.id) AND NOT EXISTS (SELECT 1 FROM auth_idempotency i WHERE i.session_id=s.id) ORDER BY s.created_at LIMIT 500 FOR UPDATE OF s SKIP LOCKED) DELETE FROM auth_sessions s USING victims v WHERE s.id=v.id");
        return removed;
    }

    @Transactional
    public void logout(String token) {
        byte[] fp = crypto.digest(token);
        Credential c = credential(fp);
        if (c == null || !c.kind.equals("refresh") || c.expires == null || !clock.instant().isBefore(c.expires)) return;
        lockSession(c.session);
        c = credential(fp);
        if (c == null || !c.kind.equals("refresh") || c.expires == null || !clock.instant().isBefore(c.expires)) return;
        jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE id=?", c.session);
    }

    private Grant createGrant(UUID account, String display, UUID existingSession) {
        UUID sid = existingSession == null ? UUID.randomUUID() : existingSession;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS), ax = now.plusSeconds(900), rx = now.plus(Duration.ofDays(30));
        if (existingSession == null)
            jdbc.update("INSERT INTO auth_sessions(id,account_id,created_at) VALUES (?,?,?)", sid, account, Timestamp.from(now));
        String access = crypto.randomToken(), refresh = crypto.randomToken();
        jdbc.update("INSERT INTO auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) VALUES (?,?,?, ?,?),(?,?,?, ?,?)", crypto.digest(access), sid, "access", Timestamp.from(now), Timestamp.from(ax), crypto.digest(refresh), sid, "refresh", Timestamp.from(now), Timestamp.from(rx));
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("id", account);
        if (display != null) acc.put("displayName", display);
        try {
            return new Grant(sid, mapper.writeValueAsBytes(Map.of("account", acc, "accessToken", access, "accessExpiresAt", ax.toString(), "refreshToken", refresh, "refreshExpiresAt", rx.toString())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeReplay(String op, UUID key, byte[] digest, byte[] fp, byte[] response, UUID session, Instant expiry) {
        String refresh;
        try {
            refresh = mapper.readTree(response).get("refreshToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("INSERT INTO auth_idempotency(operation,request_key,request_digest,credential_fingerprint,session_id,encrypted_response,refresh_fingerprint,expires_at) VALUES (?,?,?,?,?,?,?,?)", op, key, digest, fp, session, crypto.encrypt(response), crypto.digest(refresh), Timestamp.from(expiry));
    }

    private byte[] replay(String op, UUID key, byte[] identity, byte[] fp) {
        List<byte[]> rows = jdbc.query("SELECT request_digest,credential_fingerprint,encrypted_response,session_id,refresh_fingerprint,expires_at FROM auth_idempotency WHERE operation=? AND request_key=? FOR UPDATE", (rs, n) -> {
            if (!Arrays.equals(rs.getBytes(1), identity) || !Arrays.equals(rs.getBytes(2), fp))
                throw new AuthException(409, "idempotency_key_reused");
            UUID session = rs.getObject(4, UUID.class);
            byte[] refresh = rs.getBytes(5);
            Timestamp expiry = rs.getTimestamp(6);
            lockSession(session);
            Credential current = credential(refresh);
            Instant now = clock.instant();
            if (!now.isBefore(expiry.toInstant()) || !accountLiveForSession(session) || current == null || current.consumed || current.revoked || !now.isBefore(current.expires))
                throw new AuthException(401, "reauthentication_required");
            return crypto.decrypt(rs.getBytes(3));
        }, op, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Instant grantRefreshExpiry(byte[] response) {
        try {
            return Instant.parse(mapper.readTree(response).get("refreshExpiresAt").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean accountLive(UUID id) {
        return Boolean.TRUE.equals(jdbc.query("SELECT deleted_at IS NULL FROM accounts WHERE id=?", rs -> rs.next() && rs.getBoolean(1), id));
    }

    private boolean lockLiveAccount(UUID id) {
        return Boolean.TRUE.equals(jdbc.query("SELECT deleted_at IS NULL FROM accounts WHERE id=? FOR UPDATE", rs -> rs.next() && rs.getBoolean(1), id));
    }

    private boolean accountLiveForSession(UUID id) {
        return Boolean.TRUE.equals(jdbc.query("SELECT a.deleted_at IS NULL AND s.revoked_at IS NULL FROM auth_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.id=?", rs -> rs.next() && rs.getBoolean(1), id));
    }

    private Challenge challengeRow(UUID id) {
        return jdbc.query("SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=? FOR UPDATE", rs -> rs.next() ? new Challenge(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null) : null, id);
    }

    private Credential credential(byte[] fp) {
        return jdbc.query("SELECT c.session_id,c.kind,c.expires_at,c.consumed_at,s.revoked_at,s.account_id FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id WHERE c.fingerprint=?", rs -> rs.next() ? new Credential(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null, rs.getTimestamp(5) != null, rs.getObject(6, UUID.class)) : null, fp);
    }

    private void serializeKey(String op, UUID key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null, op + ":" + key);
    }

    private void lockSession(UUID id) {
        UUID account = jdbc.query("SELECT account_id FROM auth_sessions WHERE id=?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id);
        if (account == null) return;
        jdbc.query("SELECT 1 FROM accounts WHERE id=? FOR UPDATE", rs -> null, account);
        jdbc.query("SELECT 1 FROM auth_sessions WHERE id=? FOR UPDATE", rs -> null, id);
    }

    private Instant challengeCreatedAt(UUID id) {
        return jdbc.query("SELECT created_at FROM auth_challenges WHERE id=?",
                rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, id);
    }

    private record Challenge(String nonce, String purpose, Instant expires, boolean consumed) {
    }

    private record Credential(UUID session, String kind, Instant expires, boolean consumed, boolean revoked,
                              UUID account) {
    }

    private record Grant(UUID session, byte[] body) {
    }

    public static class AuthException extends RuntimeException {
        public final int status;
        public final String code;

        public AuthException(int s, String c) {
            status = s;
            code = c;
        }
    }
}
