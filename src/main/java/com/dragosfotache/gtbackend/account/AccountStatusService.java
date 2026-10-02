package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated account status on one caller-bound READ COMMITTED transaction.
 * Challenge, subject and account stages precede successful consumption; final
 * original nonce, purpose, consumption and expiry validation is immediately
 * adjacent to that consumption. Only successful owned proof consumes.
 */
@Service
public class AccountStatusService {
    private final JdbcTemplate jdbc;
    private final GoogleTokenVerifier google;
    private final Clock clock;
    private final TransactionTemplate status;
    private final AccountDeletionLedgerRepository ledger;
    private final DeletionLedgerKeys ring;
    private final DeletionLedgerCrypto crypto;

    public AccountStatusService(JdbcTemplate jdbc, GoogleTokenVerifier google, Clock clock,
                                PlatformTransactionManager transactionManager,
                                AccountDeletionLedgerRepository ledger, DeletionLedgerKeys ring,
                                DeletionLedgerCrypto crypto) {
        this.jdbc = jdbc;
        this.google = google;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.status = template;
        this.ledger = ledger;
        this.ring = ring;
        this.crypto = crypto;
    }

    public Map<String, Object> status(UUID accountId, UUID challengeId, String idToken) {
        try {
            String preliminaryNonce = preliminaryScreen(challengeId);
            String subject;
            try {
                subject = LifecycleGoogle.verify(google, idToken, preliminaryNonce,
                        StatusException::new);
            } catch (StatusException failure) {
                throw failure;
            }
            try {
                return status.execute(tx -> resolveInTransaction(accountId, challengeId,
                        subject, preliminaryNonce));
            } catch (StatusException e) {
                throw e;
            } catch (RuntimeException e) {
                throw sqlFault(e);
            }
        } catch (StatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw sqlFault(e);
        }
    }

    private Map<String, Object> resolveInTransaction(UUID accountId, UUID challengeId,
                                                     String subject, String verifiedNonce) {
        ChallengeRow challenge = lockedChallenge(challengeId);
        if (challenge == null) throw new StatusException(401, "invalid_google_credential");

        lockSubject(subject);

        AccountRow account = lockedAccount(accountId);
        UUID bound = lockedBinding(subject);

        if (bound != null && accountId.equals(bound) && account != null && !account.disabled) {
            Instant raw = clock.instant();
            requireFreshAt(challenge, verifiedNonce, raw);
            consume(challengeId, microsecondPrecision(raw));
            return DeletionReceipts.active(accountId);
        }

        AccountDeletionLedgerRepository.LedgerRow owned = ledger.findByAccount(accountId);
        if (owned != null && verifySubject(owned, subject)) {
            Instant raw = clock.instant();
            requireFreshAt(challenge, verifiedNonce, raw);
            consume(challengeId, microsecondPrecision(raw));
            return DeletionReceipts.fromLedger(owned);
        }
        throw new StatusException(404, "not_found");
    }

    private void requireFreshAt(ChallengeRow challenge, String verifiedNonce, Instant now) {
        if (challenge == null || !"accountStatus".equals(challenge.purpose) || challenge.consumed
                || !verifiedNonce.equals(challenge.nonce) || !now.isBefore(challenge.expires))
            throw new StatusException(401, "invalid_google_credential");
    }

    private void consume(UUID challengeId, Instant now) {
        if (jdbc.update("UPDATE auth_challenges SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
                Timestamp.from(now), challengeId) != 1)
            throw new StatusException(401, "invalid_google_credential");
    }

    private boolean verifySubject(AccountDeletionLedgerRepository.LedgerRow row, String subject) {
        byte[] keyBytes;
        try {
            keyBytes = ring.keyForVersion(row.subjectVersion());
        } catch (IllegalStateException e) {
            throw new StatusException(503, "temporarily_unavailable");
        }
        byte[] expected = crypto.subjectDigest(keyBytes, row.subjectVersion(), subject);
        return crypto.constantTimeEquals(expected, row.subjectDigest());
    }

    private String preliminaryScreen(UUID challengeId) {
        List<ChallengeRow> rows = jdbc.query(
                "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=?",
                (rs, row) -> new ChallengeRow(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null),
                challengeId);
        if (rows.isEmpty()) throw new StatusException(401, "invalid_google_credential");
        ChallengeRow screen = rows.get(0);
        if (!"accountStatus".equals(screen.purpose) || screen.consumed
                || !clock.instant().isBefore(screen.expires))
            throw new StatusException(401, "invalid_google_credential");
        return screen.nonce;
    }

    private ChallengeRow lockedChallenge(UUID challengeId) {
        List<ChallengeRow> rows = jdbc.query(
                "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=? FOR UPDATE",
                (rs, row) -> new ChallengeRow(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null),
                challengeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void lockSubject(String subject) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {
            while (rs.next()) {
            }
            return null;
        }, subject);
    }

    private UUID lockedBinding(String subject) {
        List<UUID> rows = jdbc.query(
                "SELECT account_id FROM google_identity_bindings WHERE google_subject=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AccountRow lockedAccount(UUID accountId) {
        List<AccountRow> rows = jdbc.query(
                "SELECT deleted_at IS NOT NULL FROM accounts WHERE id=? FOR UPDATE",
                (rs, row) -> new AccountRow(rs.getBoolean(1)), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant microsecondPrecision(Instant value) {
        return Instant.ofEpochSecond(value.getEpochSecond(), (value.getNano() / 1000) * 1000L);
    }

    private static StatusException sqlFault(RuntimeException failure) {
        boolean transientFailure = LifecycleGoogle.isRetryablePersistenceFault(failure);
        return new StatusException(transientFailure ? 503 : 500,
                transientFailure ? "temporarily_unavailable" : "internal_error");
    }

    private record ChallengeRow(String nonce, String purpose, Instant expires, boolean consumed) {
    }

    private record AccountRow(boolean disabled) {
    }

    public static class StatusException extends RuntimeException {
        public final int status;
        public final String code;

        public StatusException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }
}
