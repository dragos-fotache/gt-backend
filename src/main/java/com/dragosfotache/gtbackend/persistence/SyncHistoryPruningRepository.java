package com.dragosfotache.gtbackend.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded persistence operations for atomically pruning one account's change-history prefix. */
@Repository
public class SyncHistoryPruningRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate accountTransaction;

    public SyncHistoryPruningRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.accountTransaction = new TransactionTemplate(transactionManager);
        this.accountTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Finds only accounts whose next required batch is eligible, plus accounts with a missing
     * required batch so corruption is reported rather than silently skipped. The list is bounded.
     */
    public List<UUID> findCandidates(Instant cutoff, int accountLimit) {
        return jdbc.query("select a.id from accounts a "
                        + "join account_sync_state s on s.account_id=a.id "
                        + "left join sync_change_batches b on b.account_id=s.account_id "
                        + "and b.cursor=s.pruned_through+1 "
                        + "where a.deleted_at is null and s.committed_cursor>s.pruned_through "
                        + "and (b.cursor is null or b.committed_at<=?) "
                        + "order by b.committed_at asc nulls last,a.id asc limit ?",
                (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(cutoff), accountLimit);
    }

    /** Locks account before sync state and commits this account independently of other candidates. */
    public AccountPruneResult pruneAccount(UUID accountId, Instant cutoff, int batchLimit) {
        AccountPruneResult result = accountTransaction.execute(status -> pruneLockedAccount(accountId, cutoff, batchLimit));
        if (result == null) throw new IllegalStateException("Account cleanup transaction returned no result");
        return result;
    }

    private AccountPruneResult pruneLockedAccount(UUID accountId, Instant cutoff, int batchLimit) {
        Integer locked = jdbc.query("select id from accounts where id=? and deleted_at is null for update skip locked",
                rs -> rs.next() ? 1 : null, accountId);
        if (locked == null) return AccountPruneResult.skipped();

        SyncState state = jdbc.query("select committed_cursor,pruned_through from account_sync_state "
                        + "where account_id=? for update",
                rs -> rs.next() ? new SyncState(rs.getLong(1), rs.getLong(2)) : null, accountId);
        if (state == null || state.prunedThrough() < 0 || state.committedCursor() < state.prunedThrough())
            throw new CorruptSyncHistoryException();
        if (state.committedCursor() == state.prunedThrough()) return AccountPruneResult.unchanged();

        List<BatchBoundary> batches = readPrefixWindow(accountId, state.prunedThrough(),
                state.committedCursor(), batchLimit);
        long expectedCursor = state.prunedThrough() + 1;
        long lastEligibleCursor = state.prunedThrough();
        long changeCount = 0;
        int batchCount = 0;
        boolean stoppedAtIneligibleBatch = false;
        for (BatchBoundary batch : batches) {
            if (batch.cursor() != expectedCursor) throw new CorruptSyncHistoryException();
            if (batch.committedAt().isAfter(cutoff)) {
                stoppedAtIneligibleBatch = true;
                break;
            }
            if (batch.changeCount() <= 0) throw new CorruptSyncHistoryException();
            lastEligibleCursor = batch.cursor();
            changeCount += batch.changeCount();
            batchCount++;
            expectedCursor++;
        }
        long expectedWindowSize = Math.min((long) batchLimit, state.committedCursor() - state.prunedThrough());
        if (!stoppedAtIneligibleBatch && batches.size() < expectedWindowSize)
            throw new CorruptSyncHistoryException();
        if (batchCount == 0) {
            if (batches.isEmpty()) throw new CorruptSyncHistoryException();
            return AccountPruneResult.unchanged();
        }

        deleteChanges(accountId, state.prunedThrough(), lastEligibleCursor, changeCount);
        deleteBatches(accountId, state.prunedThrough(), lastEligibleCursor, batchCount);
        advanceWatermark(accountId, state.prunedThrough(), lastEligibleCursor);
        return AccountPruneResult.pruned(batchCount, changeCount);
    }

    private List<BatchBoundary> readPrefixWindow(UUID accountId, long prunedThrough,
                                                  long committedCursor, int batchLimit) {
        return jdbc.query("with batch_window as materialized ("
                        + "select cursor,committed_at from sync_change_batches "
                        + "where account_id=? and cursor>? and cursor<=? "
                        + "order by cursor limit ?) "
                        + "select b.cursor,b.committed_at,count(c.position) from batch_window b "
                        + "left join sync_changes c on c.account_id=? and c.cursor=b.cursor "
                        + "group by b.cursor,b.committed_at order by b.cursor",
                (rs, row) -> new BatchBoundary(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getLong(3)),
                accountId, prunedThrough, committedCursor, batchLimit, accountId);
    }

    private void deleteChanges(UUID accountId, long oldWatermark, long newWatermark, long expectedRows) {
        int deleted = jdbc.update("delete from sync_changes where account_id=? and cursor>? and cursor<=?",
                accountId, oldWatermark, newWatermark);
        if (deleted != expectedRows) throw new CorruptSyncHistoryException();
    }

    private void deleteBatches(UUID accountId, long oldWatermark, long newWatermark, int expectedRows) {
        int deleted = jdbc.update("delete from sync_change_batches where account_id=? and cursor>? and cursor<=?",
                accountId, oldWatermark, newWatermark);
        if (deleted != expectedRows) throw new CorruptSyncHistoryException();
    }

    private void advanceWatermark(UUID accountId, long oldWatermark, long newWatermark) {
        int changed = jdbc.update("update account_sync_state set pruned_through=? "
                        + "where account_id=? and pruned_through=? and committed_cursor>=?",
                newWatermark, accountId, oldWatermark, newWatermark);
        if (changed != 1) throw new CorruptSyncHistoryException();
    }

    private record SyncState(long committedCursor, long prunedThrough) { }
    private record BatchBoundary(long cursor, Instant committedAt, long changeCount) { }

    public record AccountPruneResult(Outcome outcome, int batchesPruned, long changesPruned) {
        public static AccountPruneResult pruned(int batches, long changes) {
            return new AccountPruneResult(Outcome.PRUNED, batches, changes);
        }

        public static AccountPruneResult unchanged() {
            return new AccountPruneResult(Outcome.UNCHANGED, 0, 0);
        }

        public static AccountPruneResult skipped() {
            return new AccountPruneResult(Outcome.SKIPPED, 0, 0);
        }
    }

    public enum Outcome { PRUNED, UNCHANGED, SKIPPED }

    public static final class CorruptSyncHistoryException extends RuntimeException { }
}
