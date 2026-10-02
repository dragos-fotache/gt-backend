package com.dragosfotache.gtbackend.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bound-Jdbc adapter for the immutable deletion ledger and separate pending
 * purge intent. All calls join the caller's bound transaction.
 */
@Repository
public class AccountDeletionLedgerRepository {
    private final JdbcTemplate jdbc;

    public AccountDeletionLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LedgerRow(UUID oldAccountId, int subjectVersion, byte[] subjectDigest,
                            Instant deletedAt, Instant purgeBy, UUID deletionKey,
                            int requestVersion, byte[] requestDigest) {
    }

    public LedgerRow findByAccountAndKeyForUpdate(UUID accountId, UUID key) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT old_account_id,subject_digest_version,subject_digest,deleted_at,purge_by,"
                        + "deletion_key,request_digest_version,request_digest"
                        + " FROM account_deletion_ledger WHERE old_account_id=? AND deletion_key=? FOR UPDATE",
                (rs, row) -> new LedgerRow(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getBytes(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, UUID.class), rs.getInt(7), rs.getBytes(8)),
                accountId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public LedgerRow findByAccount(UUID accountId) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT old_account_id,subject_digest_version,subject_digest,deleted_at,purge_by,"
                        + "deletion_key,request_digest_version,request_digest"
                        + " FROM account_deletion_ledger WHERE old_account_id=?",
                (rs, row) -> new LedgerRow(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getBytes(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, UUID.class), rs.getInt(7), rs.getBytes(8)),
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(UUID accountId, int subjectVersion, byte[] subjectDigest,
                       Instant deletedAt, Instant purgeBy, UUID key,
                       int requestVersion, byte[] requestDigest) {
        int inserted = jdbc.update(
                "INSERT INTO account_deletion_ledger(old_account_id,subject_digest_version,subject_digest,"
                        + "deleted_at,purge_by,deletion_key,request_digest_version,request_digest)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                accountId, subjectVersion, subjectDigest, Timestamp.from(deletedAt),
                Timestamp.from(purgeBy), key, requestVersion, requestDigest);
        if (inserted != 1) throw new IllegalStateException("Deletion ledger insert failed");
    }

    public void insertIntent(UUID accountId, Instant createdAt, Instant purgeBy) {
        int inserted = jdbc.update(
                "INSERT INTO account_purge_intents(old_account_id,created_at,purge_by,state)"
                        + " VALUES (?,?,?,'pending')",
                accountId, Timestamp.from(createdAt), Timestamp.from(purgeBy));
        if (inserted != 1) throw new IllegalStateException("Purge intent insert failed");
    }

    public List<Integer> distinctSubjectVersions() {
        return jdbc.query("SELECT DISTINCT subject_digest_version FROM account_deletion_ledger",
                (rs, row) -> rs.getInt(1));
    }

    public Instant latestDeletionForDigest(int version, byte[] digest) {
        List<Timestamp> rows = jdbc.query(
                "SELECT deleted_at FROM account_deletion_ledger"
                        + " WHERE subject_digest_version=? AND subject_digest=? ORDER BY deleted_at DESC LIMIT 1",
                (rs, row) -> rs.getTimestamp(1), version, digest);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }
}
