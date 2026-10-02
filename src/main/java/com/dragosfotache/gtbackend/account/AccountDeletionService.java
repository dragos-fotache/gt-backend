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
 * Atomic deletion acceptance on one caller-bound READ COMMITTED transaction.
 * Initial scoped exact replay precedes gate and provider work without locks;
 * the authoritative key-serialized replay precedes all denial inside the
 * transaction. Every blocking admission step precedes a single unrounded
 * service-time freshness sample, and conditional consumption immediately
 * follows that decision. Successful proof is consumed in the same commit as
 * the ledger, intent, disablement, revocation and detachment.
 */
@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    private final GoogleTokenVerifier google;
    private final Clock clock;
    private final TransactionTemplate acceptance;
    private final AccountDeletionLedgerRepository ledger;
    private final DeletionLedgerKeys ring;
    private final DeletionLedgerCrypto crypto;
    private final PurgeReadiness readiness;
    private final AccountDeletionHooks hooks;

    public AccountDeletionService(JdbcTemplate jdbc, GoogleTokenVerifier google, Clock clock,
                                  PlatformTransactionManager transactionManager,
                                  AccountDeletionLedgerRepository ledger, DeletionLedgerKeys ring,
                                  DeletionLedgerCrypto crypto, PurgeReadiness readiness,
                                  AccountDeletionHooks hooks) {
        this.jdbc = jdbc;
        this.google = google;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.acceptance = template;
        this.ledger = ledger;
        this.ring = ring;
        this.crypto = crypto;
        this.readiness = readiness;
        this.hooks = hooks;
    }

    public Map<String, Object> delete(UUID accountId, UUID challengeId, String idToken,
                                      UUID key, byte[] exactBody) {
        try {
            Map<String, Object> fast = initialReplay(accountId, key, exactBody);
            if (fast != null) return fast;
            if (!readiness.isPurgeCapable()) {
                Map<String, Object> recovered = serializedRecheck(accountId, key, exactBody);
                if (recovered != null) return recovered;
                throw new DeletionException(503, "temporarily_unavailable");
            }
            String preliminaryNonce;
            String subject;
            try {
                preliminaryNonce = preliminaryScreen(challengeId);
                subject = LifecycleGoogle.verify(google, idToken, preliminaryNonce,
                        DeletionException::new);
            } catch (DeletionException failure) {
                Map<String, Object> recovered = serializedRecheck(accountId, key, exactBody);
                if (recovered != null) return recovered;
                throw failure;
            }
            try {
                return acceptance.execute(tx -> acceptInTransaction(accountId, challengeId,
                        subject, preliminaryNonce, key, exactBody));
            } catch (DeletionException e) {
                throw e;
            } catch (RuntimeException e) {
                throw sqlFault(e);
            }
        } catch (DeletionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw sqlFault(e);
        }
    }

    private Map<String, Object> initialReplay(UUID accountId, UUID key, byte[] exactBody) {
        AccountDeletionLedgerRepository.LedgerRow row = ledger.findByAccount(accountId);
        if (row == null || !row.deletionKey().equals(key)) return null;
        return receiptOrConflict(row, accountId, key, exactBody);
    }

    private Map<String, Object> serializedRecheck(UUID accountId, UUID key, byte[] exactBody) {
        try {
            return acceptance.execute(tx -> {
                lockScopedKey(accountId, key);
                AccountDeletionLedgerRepository.LedgerRow row =
                        ledger.findByAccountAndKeyForUpdate(accountId, key);
                if (row == null) return null;
                return receiptOrConflict(row, accountId, key, exactBody);
            });
        } catch (DeletionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw sqlFault(e);
        }
    }

    private Map<String, Object> acceptInTransaction(UUID accountId, UUID challengeId, String subject,
                                                    String verifiedNonce, UUID key, byte[] exactBody) {
        lockScopedKey(accountId, key);
        hooks.afterScopedKeyLock();

        AccountDeletionLedgerRepository.LedgerRow immediate =
                ledger.findByAccountAndKeyForUpdate(accountId, key);
        if (immediate != null) return receiptOrConflict(immediate, accountId, key, exactBody);

        if (!readiness.isPurgeCapable()) throw new DeletionException(503, "temporarily_unavailable");

        ChallengeRow challenge = lockedChallenge(challengeId);
        if (challenge == null) throw new DeletionException(401, "invalid_google_credential");

        lockSubject(subject);

        AccountRow account = lockedAccount(accountId);
        UUID bound = lockedBinding(subject);

        AccountDeletionLedgerRepository.LedgerRow late =
                ledger.findByAccountAndKeyForUpdate(accountId, key);
        if (late != null) return receiptOrConflict(late, accountId, key, exactBody);

        boolean live = account != null && !account.disabled && accountId.equals(bound);
        if (!live) {
            AccountDeletionLedgerRepository.LedgerRow owned = ledger.findByAccount(accountId);
            if (owned != null && verifySubject(owned, subject)) {
                requireFreshAt(challenge, verifiedNonce, clock.instant(), DeletionException::new);
                throw new DeletionException(409, "invalid_state");
            }
            throw new DeletionException(404, "not_found");
        }

        AcceptanceInputs inputs = prepareAcceptance(accountId, key, exactBody, subject);
        hooks.afterFinalChecks();

        Instant rawNow = clock.instant();
        requireFreshAt(challenge, verifiedNonce, rawNow, DeletionException::new);
        Instant now = microsecondPrecision(rawNow);

        return applyAcceptance(accountId, challengeId, subject, key, inputs, now);
    }

    private record AcceptanceInputs(int requestVersion, byte[] expected, byte[] subjectDigest,
                                    List<UUID> sessions) {
    }

    private AcceptanceInputs prepareAcceptance(UUID accountId, UUID key, byte[] exactBody,
                                               String subject) {
        if (!ring.isConfigured()) throw new DeletionException(503, "temporarily_unavailable");
        int requestVersion = ring.activeVersion();
        byte[] requestKey = ring.keyForVersion(requestVersion);
        byte[] expected = crypto.requestDigest(requestKey, requestVersion, accountId, key, exactBody);
        byte[] subjectDigest = crypto.subjectDigest(requestKey, requestVersion, subject);
        List<UUID> sessions = jdbc.query("SELECT id FROM auth_sessions WHERE account_id=? ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), accountId);
        return new AcceptanceInputs(requestVersion, expected, subjectDigest, sessions);
    }

    private Map<String, Object> applyAcceptance(UUID accountId, UUID challengeId, String subject,
                                                UUID key, AcceptanceInputs inputs, Instant now) {
        if (jdbc.update("UPDATE auth_challenges SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
                Timestamp.from(now), challengeId) != 1)
            throw new DeletionException(401, "invalid_google_credential");

        ledger.insert(accountId, inputs.requestVersion(), inputs.subjectDigest(), now,
                now.plusSeconds(86400), key, inputs.requestVersion(), inputs.expected());
        ledger.insertIntent(accountId, now, now.plusSeconds(86400));

        if (jdbc.update("UPDATE accounts SET deleted_at=? WHERE id=? AND deleted_at IS NULL",
                Timestamp.from(now), accountId) != 1)
            throw new IllegalStateException("Account disablement failed");

        int revoked = jdbc.update(
                "UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE account_id=?",
                Timestamp.from(now), accountId);
        if (revoked != inputs.sessions().size()) throw new IllegalStateException("Session revocation mismatch");

        if (jdbc.update("DELETE FROM google_identity_bindings WHERE google_subject=? AND account_id=?",
                subject, accountId) != 1)
            throw new IllegalStateException("Identity detachment failed");

        hooks.beforeCommit();
        AccountDeletionLedgerRepository.LedgerRow persisted =
                ledger.findByAccountAndKeyForUpdate(accountId, key);
        if (persisted == null) throw new IllegalStateException("Deletion ledger persistence failed");
        return DeletionReceipts.fromLedger(persisted);
    }

    private Map<String, Object> receiptOrConflict(AccountDeletionLedgerRepository.LedgerRow row,
                                                  UUID accountId, UUID key, byte[] exactBody) {
        byte[] keyBytes;
        try {
            keyBytes = ring.keyForVersion(row.requestVersion());
        } catch (IllegalStateException e) {
            throw new DeletionException(503, "temporarily_unavailable");
        }
        byte[] expected = crypto.requestDigest(keyBytes, row.requestVersion(), accountId, key, exactBody);
        if (!crypto.constantTimeEquals(expected, row.requestDigest()))
            throw new DeletionException(409, "idempotency_key_reused");
        return DeletionReceipts.fromLedger(row);
    }

    private boolean verifySubject(AccountDeletionLedgerRepository.LedgerRow row, String subject) {
        byte[] keyBytes;
        try {
            keyBytes = ring.keyForVersion(row.subjectVersion());
        } catch (IllegalStateException e) {
            throw new DeletionException(503, "temporarily_unavailable");
        }
        byte[] expected = crypto.subjectDigest(keyBytes, row.subjectVersion(), subject);
        return crypto.constantTimeEquals(expected, row.subjectDigest());
    }

    private static void requireFreshAt(ChallengeRow challenge, String verifiedNonce, Instant now,
                                       LifecycleGoogle.ProofFailure failures) {
        if (challenge == null || !"deleteAccount".equals(challenge.purpose) || challenge.consumed
                || !verifiedNonce.equals(challenge.nonce) || !now.isBefore(challenge.expires))
            throw failures.failure(401, "invalid_google_credential");
    }

    private String preliminaryScreen(UUID challengeId) {
        List<ChallengeRow> rows = jdbc.query(
                "SELECT nonce,purpose,expires_at,consumed_at FROM auth_challenges WHERE id=?",
                (rs, row) -> new ChallengeRow(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null),
                challengeId);
        if (rows.isEmpty()) throw new DeletionException(401, "invalid_google_credential");
        ChallengeRow screen = rows.get(0);
        if (!"deleteAccount".equals(screen.purpose) || screen.consumed
                || !clock.instant().isBefore(screen.expires))
            throw new DeletionException(401, "invalid_google_credential");
        return screen.nonce;
    }

    private void lockScopedKey(UUID accountId, UUID key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null,
                "deletion:" + accountId + ":" + key);
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

    private static DeletionException sqlFault(RuntimeException failure) {
        boolean transientFailure = LifecycleGoogle.isRetryablePersistenceFault(failure);
        return new DeletionException(transientFailure ? 503 : 500,
                transientFailure ? "temporarily_unavailable" : "internal_error");
    }

    private record ChallengeRow(String nonce, String purpose, Instant expires, boolean consumed) {
    }

    private record AccountRow(boolean disabled) {
    }

    public static class DeletionException extends RuntimeException {
        public final int status;
        public final String code;

        public DeletionException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }
}
