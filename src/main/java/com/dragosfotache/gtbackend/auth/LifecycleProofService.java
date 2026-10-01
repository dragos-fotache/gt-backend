package com.dragosfotache.gtbackend.auth;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reusable non-signup fresh-proof boundary for account lifecycle routes (export now;
 * deletion and status later). Verifies provider proof, authorizes the specified account
 * binding, and durably consumes the one-use challenge. It never provisions accounts,
 * rebinds identities, or mints grants; the signup-capable exchange path stays untouched.
 * Bound reads join the caller's thread-bound transaction; consumption and the
 * preliminary screen use raw pooled connections that bypass it.
 */
@Service
public class LifecycleProofService {
    private final JdbcTemplate bound;
    private final DataSource raw;
    private final GoogleTokenVerifier google;
    private final Clock clock;

    public LifecycleProofService(JdbcTemplate jdbc, DataSource dataSource,
                                 GoogleTokenVerifier google, Clock clock) {
        if (dataSource instanceof TransactionAwareDataSourceProxy)
            throw new IllegalStateException("Lifecycle proof requires the raw pooled DataSource");
        this.bound = jdbc;
        this.raw = dataSource;
        this.google = google;
        this.clock = clock;
    }

    public record VerifiedProof(UUID accountId, UUID challengeId, String subject, String nonce) {
    }

    public record ChallengeScreen(String nonce, String purpose, Instant expires, boolean consumed) {
    }

    /**
     * Raw preliminary read for the provider call. No locks are held and no read
     * snapshot is opened.
     */
    public ChallengeScreen preliminaryExportScreen(UUID challengeId) {
        ChallengeScreen screen = selectRaw(challengeId);
        if (screen == null || !"export".equals(screen.purpose) || screen.consumed
                || !clock.instant().isBefore(screen.expires))
            throw new ProofException(401, "invalid_google_credential");
        return screen;
    }

    /**
     * Provider verification outside all database locks. Only genuine provider
     * rejections are 401; provider transport failure is 503; unexpected faults
     * keep their 503/500 classification.
     */
    public String verifyGoogle(String token, String nonce) {
        try {
            return google.verify(token, nonce).subject();
        } catch (GoogleTokenVerifier.InvalidGoogleToken e) {
            throw new ProofException(401, "invalid_google_credential");
        } catch (GoogleTokenVerifier.ProviderUnavailable e) {
            throw new ProofException(503, "temporarily_unavailable");
        } catch (RuntimeException e) {
            throw new ProofException(isTransient(e) ? 503 : 500,
                    isTransient(e) ? "temporarily_unavailable" : "internal_error");
        }
    }

    /** Bound snapshot read; joins the caller's thread-bound transaction. */
    public ChallengeScreen snapshotScreen(UUID challengeId) {
        List<ChallengeScreen> rows = bound.query(
                "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=?",
                (rs, row) -> new ChallengeScreen(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null),
                challengeId);
        if (rows.isEmpty()) throw new ProofException(401, "invalid_google_credential");
        return rows.get(0);
    }

    /**
     * Bound binding and liveness authorization. Called only after verified provider
     * proof, so a 404 here discloses nothing to an unverified caller.
     */
    public void authorizeSnapshot(UUID accountId, String subject) {
        List<UUID> rows = bound.query(
                "SELECT account_id FROM google_identity_bindings WHERE google_subject=?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        UUID boundAccount = rows.isEmpty() ? null : rows.get(0);
        if (boundAccount == null || !accountId.equals(boundAccount) || !accountLive(accountId))
            throw new ProofException(404, "not_found");
    }

    /**
     * Durable consumption on a separate raw connection: challenge row, subject
     * advisory, then account row, with the freshness recheck immediately before the
     * conditional update, after both lock waits. Rejections roll back explicitly
     * without consuming. An uncertain commit outcome is in-doubt, never restored.
     */
    public void consume(VerifiedProof proof) {
        try (Connection connection = raw.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ChallengeScreen locked = selectChallenge(connection, true, proof.challengeId());
                if (locked == null || !"export".equals(locked.purpose) || locked.consumed
                        || !proof.nonce().equals(locked.nonce))
                    throw new ProofException(401, "invalid_google_credential");
                advisoryLock(connection, proof.subject());
                if (!proof.accountId().equals(selectBinding(connection, proof.subject()))
                        || !selectAccountForUpdate(connection, proof.accountId()))
                    throw new ProofException(404, "not_found");
                ChallengeScreen latest = selectChallenge(connection, false, proof.challengeId());
                if (latest == null || !"export".equals(latest.purpose) || latest.consumed
                        || !proof.nonce().equals(latest.nonce)
                        || !clock.instant().isBefore(latest.expires))
                    throw new ProofException(401, "invalid_google_credential");
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE auth_challenges SET consumed_at=clock_timestamp()"
                                + " WHERE id=? AND consumed_at IS NULL")) {
                    update.setObject(1, proof.challengeId());
                    if (update.executeUpdate() != 1)
                        throw new ProofException(401, "invalid_google_credential");
                }
                try {
                    connection.commit();
                } catch (SQLException e) {
                    throw new ProofInDoubt();
                }
            } catch (ProofException e) {
                rollbackQuietly(connection);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw sqlFault(e);
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw sqlFault(e);
        }
    }

    private ChallengeScreen selectRaw(UUID id) {
        try (Connection connection = raw.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=?")) {
            query.setObject(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) return null;
                return new ChallengeScreen(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null);
            }
        } catch (SQLException e) {
            throw sqlFault(e);
        }
    }

    private boolean accountLive(UUID id) {
        return Boolean.TRUE.equals(bound.query(
                "SELECT deleted_at IS NULL FROM accounts WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), id));
    }

    private ChallengeScreen selectChallenge(Connection connection, boolean forUpdate, UUID id)
            throws SQLException {
        String sql = "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) return null;
                return new ChallengeScreen(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null);
            }
        }
    }

    private UUID selectBinding(Connection connection, String subject) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT account_id FROM google_identity_bindings WHERE google_subject=?")) {
            query.setString(1, subject);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private boolean selectAccountForUpdate(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT deleted_at IS NULL FROM accounts WHERE id=? FOR UPDATE")) {
            query.setObject(1, id);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void advisoryLock(Connection connection, String subject) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            lock.setString(1, subject);
            try (ResultSet rs = lock.executeQuery()) {
                while (rs.next()) {
                    // Drain the advisory result; VOID carries no columns to decode.
                }
            }
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            // The original sanitized error is already being propagated.
        }
    }

    /**
     * Safe SQL classification for raw proof connections: connection, acquisition,
     * serialization, deadlock, and transient faults are retryable; genuinely
     * unexpected SQL faults are internal. Commit outcomes stay in-doubt and are
     * handled separately. No raw cause is attached to the sanitized error.
     */
    private static ProofException sqlFault(SQLException e) {
        if (e instanceof SQLTransientException) return new ProofException(503, "temporarily_unavailable");
        String state = e.getSQLState();
        if (state != null && (state.startsWith("08") || state.startsWith("40")))
            return new ProofException(503, "temporarily_unavailable");
        return new ProofException(500, "internal_error");
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("40")))
                return true;
            if (cause instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    public static class ProofException extends RuntimeException {
        public final int status;
        public final String code;

        public ProofException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }

    public static final class ProofInDoubt extends ProofException {
        public ProofInDoubt() {
            super(503, "temporarily_unavailable");
        }
    }
}
