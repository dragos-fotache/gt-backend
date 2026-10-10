package com.dragosfotache.gtbackend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Invoke inside every protected write transaction and hold these locks through commit.
 */
@Component
public class SessionAuthorization {
    private final JdbcTemplate jdbc;
    private final AuthCrypto crypto;
    private final Clock clock;

    public SessionAuthorization(JdbcTemplate jdbc, AuthCrypto crypto, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.clock = clock;
    }

    /**
     * Read authorization for a caller-owned read-only repeatable-read transaction. This deliberately
     * takes no row locks; write paths must continue to use {@link #requireLiveAccess(String)}.
     */
    public UUID requireLiveAccessForRead(String opaqueToken) {
        byte[] fingerprint = crypto.digest(opaqueToken);
        Record r = jdbc.query("SELECT s.account_id,s.id,c.expires_at,a.deleted_at,s.revoked_at "
                        + "FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id "
                        + "JOIN accounts a ON a.id=s.account_id WHERE c.fingerprint=? AND c.kind='access'",
                rs -> rs.next() ? new Record(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null, rs.getTimestamp(5) != null) : null,
                fingerprint);
        if (r == null || r.deleted || r.revoked || !clock.instant().isBefore(r.expires))
            throw new AuthService.AuthException(401, "authentication_required");
        return r.account;
    }

    @Transactional
    public UUID requireLiveAccess(String opaqueToken) {
        byte[] fingerprint = crypto.digest(opaqueToken);
        Record resolved = jdbc.query("SELECT s.account_id,s.id,c.expires_at,a.deleted_at,s.revoked_at FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id JOIN accounts a ON a.id=s.account_id WHERE c.fingerprint=? AND c.kind='access'", rs -> rs.next() ? new Record(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null, rs.getTimestamp(5) != null) : null, fingerprint);
        if (resolved == null) throw new AuthService.AuthException(401, "authentication_required");
        jdbc.query("SELECT 1 FROM accounts WHERE id=? FOR UPDATE", rs -> null, resolved.account);
        jdbc.query("SELECT 1 FROM auth_sessions WHERE id=? FOR UPDATE", rs -> null, resolved.session);
        Record checked = jdbc.query("SELECT s.account_id,s.id,c.expires_at,a.deleted_at,s.revoked_at FROM auth_credentials c JOIN auth_sessions s ON s.id=c.session_id JOIN accounts a ON a.id=s.account_id WHERE c.fingerprint=? AND c.kind='access' FOR UPDATE OF c",
                rs -> rs.next() ? new Record(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null, rs.getTimestamp(5) != null) : null, fingerprint);
        if (checked == null || !checked.account.equals(resolved.account) || !checked.session.equals(resolved.session)
                || checked.deleted || checked.revoked || !clock.instant().isBefore(checked.expires))
            throw new AuthService.AuthException(401, "authentication_required");
        return checked.account;
    }

    private record Record(UUID account, UUID session, java.time.Instant expires, boolean deleted, boolean revoked) {
    }
}
