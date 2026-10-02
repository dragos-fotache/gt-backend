package com.dragosfotache.gtbackend.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bound-Jdbc adapter for account purge. Every destructive statement is scoped
 * by old-account UUID and bounded by the caller's row limit; no statement is
 * scoped by provider subject, credential key, resource UUID or latest binding.
 * All calls join the caller's bound transaction.
 */
@Repository
public class AccountPurgeRepository {
    private final JdbcTemplate jdbc;

    public AccountPurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record IntentRow(UUID oldAccountId, String state, String phase, int attempts,
                            Instant purgeBy, Instant completedAt, String lastError) {
    }

    /**
     * Claims at most {@code limit} pending or in-progress intents ordered by
     * {@code purge_by}. Holds row locks to the caller commit; the caller must
     * be inside a transaction. Pending rows move to {@code in_progress} as
     * part of the claim.
     */
    public List<UUID> claimDueIntents(int limit) {
        List<UUID> ids = jdbc.query(
                "SELECT old_account_id FROM account_purge_intents"
                        + " WHERE state IN ('pending','in_progress')"
                        + " ORDER BY purge_by ASC, old_account_id ASC LIMIT ?"
                        + " FOR UPDATE SKIP LOCKED",
                (rs, row) -> rs.getObject(1, UUID.class), limit);
        for (UUID id : ids) {
            jdbc.update("UPDATE account_purge_intents SET state='in_progress',"
                    + " updated_at=clock_timestamp() WHERE old_account_id=? AND state='pending'", id);
        }
        return ids;
    }

    public IntentRow lockIntent(UUID oldAccountId) {
        List<IntentRow> rows = jdbc.query(
                "SELECT old_account_id,state,phase,attempts,purge_by,completed_at,last_error"
                        + " FROM account_purge_intents WHERE old_account_id=? FOR UPDATE",
                (rs, row) -> new IntentRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getString(7)),
                oldAccountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Moves a pending intent to {@code in_progress}, mirroring the scheduler
     * claim transition for direct single-account invocations. No-op when the
     * row is already beyond pending.
     */
    public void beginProgress(UUID oldAccountId, Instant now) {
        jdbc.update("UPDATE account_purge_intents SET state='in_progress', updated_at=?"
                + " WHERE old_account_id=? AND state='pending'",
                Timestamp.from(now), oldAccountId);
    }

    public void markPhase(UUID oldAccountId, String phase, Instant now) {
        jdbc.update("UPDATE account_purge_intents SET phase=?, updated_at=? WHERE old_account_id=?",
                phase, Timestamp.from(now), oldAccountId);
    }

    public void noteFault(UUID oldAccountId, String sanitizedCode, Instant now) {
        jdbc.update("UPDATE account_purge_intents SET state='pending', attempts=attempts+1,"
                + " last_error=?, updated_at=? WHERE old_account_id=?",
                sanitizedCode, Timestamp.from(now), oldAccountId);
    }

    public void markComplete(UUID oldAccountId, Instant now) {
        int updated = jdbc.update("UPDATE account_purge_intents SET state='complete',"
                + " phase='complete', completed_at=?, last_error=NULL, updated_at=?"
                + " WHERE old_account_id=? AND state='in_progress'",
                Timestamp.from(now), Timestamp.from(now), oldAccountId);
        if (updated != 1) throw new IllegalStateException("Purge intent completion failed");
    }

    /** Locks the disabled account anchor; returns false when the row is gone. */
    public boolean lockAccount(UUID oldAccountId) {
        List<Integer> rows = jdbc.query("SELECT 1 FROM accounts WHERE id=? FOR UPDATE",
                (rs, row) -> 1, oldAccountId);
        return !rows.isEmpty();
    }

    private int deleteBounded(String sql, UUID oldAccountId, int limit) {
        return jdbc.update(sql, oldAccountId, limit);
    }

    public int deleteWorkoutSets(UUID a, int limit) {
        return deleteBounded("DELETE FROM workout_sets s USING ("
                + "SELECT s2.ctid FROM workout_sets s2"
                + " JOIN workout_exercises e ON e.id=s2.workout_exercise_id"
                + " JOIN workouts w ON w.id=e.workout_id WHERE w.owner_id=?"
                + " ORDER BY s2.ctid LIMIT ?) v WHERE s.ctid=v.ctid", a, limit);
    }

    public int deleteRoutineSets(UUID a, int limit) {
        return deleteBounded("DELETE FROM routine_sets s USING ("
                + "SELECT s2.ctid FROM routine_sets s2"
                + " JOIN routine_exercises e ON e.id=s2.routine_exercise_id"
                + " JOIN routines r ON r.id=e.routine_id WHERE r.owner_id=?"
                + " ORDER BY s2.ctid LIMIT ?) v WHERE s.ctid=v.ctid", a, limit);
    }

    public int deleteWorkoutExercises(UUID a, int limit) {
        return deleteBounded("DELETE FROM workout_exercises e USING ("
                + "SELECT e2.ctid FROM workout_exercises e2"
                + " JOIN workouts w ON w.id=e2.workout_id WHERE w.owner_id=?"
                + " ORDER BY e2.ctid LIMIT ?) v WHERE e.ctid=v.ctid", a, limit);
    }

    public int deleteRoutineExercises(UUID a, int limit) {
        return deleteBounded("DELETE FROM routine_exercises e USING ("
                + "SELECT e2.ctid FROM routine_exercises e2"
                + " JOIN routines r ON r.id=e2.routine_id WHERE r.owner_id=?"
                + " ORDER BY e2.ctid LIMIT ?) v WHERE e.ctid=v.ctid", a, limit);
    }

    public int deleteWorkoutRoots(UUID a, int limit) {
        return deleteBounded("DELETE FROM workouts w USING ("
                + "SELECT ctid FROM workouts WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE w.ctid=v.ctid", a, limit);
    }

    public int deleteRoutineRoots(UUID a, int limit) {
        return deleteBounded("DELETE FROM routines r USING ("
                + "SELECT ctid FROM routines WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE r.ctid=v.ctid", a, limit);
    }

    public int deleteExerciseMuscleGroups(UUID a, int limit) {
        return deleteBounded("DELETE FROM exercise_muscle_groups m USING ("
                + "SELECT m2.ctid FROM exercise_muscle_groups m2"
                + " JOIN exercises x ON x.id=m2.exercise_id WHERE x.owner_id=?"
                + " ORDER BY m2.ctid LIMIT ?) v WHERE m.ctid=v.ctid", a, limit);
    }

    public int deleteExerciseEquipment(UUID a, int limit) {
        return deleteBounded("DELETE FROM exercise_equipment e USING ("
                + "SELECT e2.ctid FROM exercise_equipment e2"
                + " JOIN exercises x ON x.id=e2.exercise_id WHERE x.owner_id=?"
                + " ORDER BY e2.ctid LIMIT ?) v WHERE e.ctid=v.ctid", a, limit);
    }

    public int deleteOwnedExercises(UUID a, int limit) {
        return deleteBounded("DELETE FROM exercises x USING ("
                + "SELECT ctid FROM exercises WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE x.ctid=v.ctid", a, limit);
    }

    public int deleteChildReservations(UUID a, int limit) {
        return deleteBounded("DELETE FROM child_id_reservations r USING ("
                + "SELECT ctid FROM child_id_reservations WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE r.ctid=v.ctid", a, limit);
    }

    public int deleteRootReservations(UUID a, int limit) {
        return deleteBounded("DELETE FROM root_id_reservations r USING ("
                + "SELECT ctid FROM root_id_reservations WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE r.ctid=v.ctid", a, limit);
    }

    public int deleteSyncChanges(UUID a, int limit) {
        return deleteBounded("DELETE FROM sync_changes c USING ("
                + "SELECT ctid FROM sync_changes WHERE account_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE c.ctid=v.ctid", a, limit);
    }

    public int deleteSyncBatches(UUID a, int limit) {
        return jdbc.update("DELETE FROM sync_change_batches b USING ("
                + "SELECT b2.ctid FROM sync_change_batches b2 WHERE b2.account_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM sync_changes c WHERE c.account_id=b2.account_id"
                + " AND c.cursor=b2.cursor) ORDER BY b2.ctid LIMIT ?) v"
                + " WHERE b.ctid=v.ctid", a, limit);
    }

    public int deleteAccountSyncState(UUID a) {
        return jdbc.update("DELETE FROM account_sync_state WHERE account_id=?", a);
    }

    public int deleteSnapshotRecords(UUID a, int limit) {
        return deleteBounded("DELETE FROM sync_snapshot_records r USING ("
                + "SELECT ctid FROM sync_snapshot_records WHERE account_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE r.ctid=v.ctid", a, limit);
    }

    public int deleteSnapshotHeaders(UUID a, int limit) {
        return jdbc.update("DELETE FROM sync_snapshots s USING ("
                + "SELECT s2.ctid FROM sync_snapshots s2 WHERE s2.account_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM sync_snapshot_records r"
                + " WHERE r.account_id=s2.account_id AND r.snapshot_id=s2.snapshot_id)"
                + " ORDER BY s2.ctid LIMIT ?) v WHERE s.ctid=v.ctid", a, limit);
    }

    public int deleteCursorTokens(UUID a, int limit) {
        return jdbc.update("DELETE FROM sync_cursor_tokens t USING ("
                + "SELECT t2.ctid FROM sync_cursor_tokens t2 WHERE t2.account_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM sync_snapshots s"
                + " WHERE s.account_id=t2.account_id AND s.baseline_cursor=t2.token)"
                + " ORDER BY t2.ctid LIMIT ?) v WHERE t.ctid=v.ctid", a, limit);
    }

    public int deleteProcessedMutations(UUID a, int limit) {
        return deleteBounded("DELETE FROM processed_mutations m USING ("
                + "SELECT ctid FROM processed_mutations WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE m.ctid=v.ctid", a, limit);
    }

    public int deleteResourceMutations(UUID a, int limit) {
        return deleteBounded("DELETE FROM resource_mutations m USING ("
                + "SELECT ctid FROM resource_mutations WHERE owner_id=? ORDER BY ctid LIMIT ?) v"
                + " WHERE m.ctid=v.ctid", a, limit);
    }

    public int deleteAuthIdempotency(UUID a, int limit) {
        return jdbc.update("DELETE FROM auth_idempotency i USING ("
                + "SELECT i2.ctid FROM auth_idempotency i2"
                + " JOIN auth_sessions s ON s.id=i2.session_id WHERE s.account_id=?"
                + " ORDER BY i2.ctid LIMIT ?) v WHERE i.ctid=v.ctid", a, limit);
    }

    public int deleteAuthCredentials(UUID a, int limit) {
        return jdbc.update("DELETE FROM auth_credentials c USING ("
                + "SELECT c2.ctid FROM auth_credentials c2"
                + " JOIN auth_sessions s ON s.id=c2.session_id WHERE s.account_id=?"
                + " ORDER BY c2.ctid LIMIT ?) v WHERE c.ctid=v.ctid", a, limit);
    }

    public int deleteAuthSessions(UUID a, int limit) {
        return jdbc.update("DELETE FROM auth_sessions s USING ("
                + "SELECT s2.ctid FROM auth_sessions s2 WHERE s2.account_id=?"
                + " AND NOT EXISTS (SELECT 1 FROM auth_credentials c WHERE c.session_id=s2.id)"
                + " AND NOT EXISTS (SELECT 1 FROM auth_idempotency i WHERE i.session_id=s2.id)"
                + " ORDER BY s2.ctid LIMIT ?) v WHERE s.ctid=v.ctid", a, limit);
    }

    public int deleteBindings(UUID a) {
        return jdbc.update("DELETE FROM google_identity_bindings WHERE account_id=?", a);
    }

    /**
     * Reads the disabled anchor: null when the account row is gone (crash
     * resume proceeds), true when present and disabled, false when present
     * and live. Locks the row to the caller commit.
     */
    public Boolean readAccountDisabled(UUID oldAccountId) {
        List<Timestamp> rows = jdbc.query("SELECT deleted_at FROM accounts WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getTimestamp(1), oldAccountId);
        if (rows.isEmpty()) return null;
        return rows.get(0) != null;
    }

    public int deleteAccountRow(UUID a) {
        return jdbc.update("DELETE FROM accounts WHERE id=? AND deleted_at IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM sync_snapshots s WHERE s.account_id=accounts.id)", a);
    }

    private long count(String sql, UUID a) {
        Long value = jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null, a);
        return value == null ? 0L : value;
    }

    /**
     * Verification counts across every account-bearing store. All values must
     * be zero before the intent may be marked complete. The deletion ledger
     * itself is not counted here: its row is the retained whitelist.
     */
    public Map<String, Long> verifyCounts(UUID a) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("workout_sets", count("SELECT COUNT(*) FROM workout_sets s"
                + " JOIN workout_exercises e ON e.id=s.workout_exercise_id"
                + " JOIN workouts w ON w.id=e.workout_id WHERE w.owner_id=?", a));
        counts.put("routine_sets", count("SELECT COUNT(*) FROM routine_sets s"
                + " JOIN routine_exercises e ON e.id=s.routine_exercise_id"
                + " JOIN routines r ON r.id=e.routine_id WHERE r.owner_id=?", a));
        counts.put("workout_exercises", count("SELECT COUNT(*) FROM workout_exercises e"
                + " JOIN workouts w ON w.id=e.workout_id WHERE w.owner_id=?", a));
        counts.put("routine_exercises", count("SELECT COUNT(*) FROM routine_exercises e"
                + " JOIN routines r ON r.id=e.routine_id WHERE r.owner_id=?", a));
        counts.put("workouts", count("SELECT COUNT(*) FROM workouts WHERE owner_id=?", a));
        counts.put("routines", count("SELECT COUNT(*) FROM routines WHERE owner_id=?", a));
        counts.put("exercise_muscle_groups", count("SELECT COUNT(*) FROM exercise_muscle_groups m"
                + " JOIN exercises x ON x.id=m.exercise_id WHERE x.owner_id=?", a));
        counts.put("exercise_equipment", count("SELECT COUNT(*) FROM exercise_equipment e"
                + " JOIN exercises x ON x.id=e.exercise_id WHERE x.owner_id=?", a));
        counts.put("exercises", count("SELECT COUNT(*) FROM exercises WHERE owner_id=?", a));
        counts.put("child_id_reservations",
                count("SELECT COUNT(*) FROM child_id_reservations WHERE owner_id=?", a));
        counts.put("root_id_reservations",
                count("SELECT COUNT(*) FROM root_id_reservations WHERE owner_id=?", a));
        counts.put("sync_changes", count("SELECT COUNT(*) FROM sync_changes WHERE account_id=?", a));
        counts.put("sync_change_batches",
                count("SELECT COUNT(*) FROM sync_change_batches WHERE account_id=?", a));
        counts.put("account_sync_state",
                count("SELECT COUNT(*) FROM account_sync_state WHERE account_id=?", a));
        counts.put("sync_snapshot_records",
                count("SELECT COUNT(*) FROM sync_snapshot_records WHERE account_id=?", a));
        counts.put("sync_snapshots", count("SELECT COUNT(*) FROM sync_snapshots WHERE account_id=?", a));
        counts.put("sync_cursor_tokens",
                count("SELECT COUNT(*) FROM sync_cursor_tokens WHERE account_id=?", a));
        counts.put("processed_mutations",
                count("SELECT COUNT(*) FROM processed_mutations WHERE owner_id=?", a));
        counts.put("resource_mutations",
                count("SELECT COUNT(*) FROM resource_mutations WHERE owner_id=?", a));
        counts.put("auth_idempotency", count("SELECT COUNT(*) FROM auth_idempotency i"
                + " JOIN auth_sessions s ON s.id=i.session_id WHERE s.account_id=?", a));
        counts.put("auth_credentials", count("SELECT COUNT(*) FROM auth_credentials c"
                + " JOIN auth_sessions s ON s.id=c.session_id WHERE s.account_id=?", a));
        counts.put("auth_sessions", count("SELECT COUNT(*) FROM auth_sessions WHERE account_id=?", a));
        counts.put("google_identity_bindings",
                count("SELECT COUNT(*) FROM google_identity_bindings WHERE account_id=?", a));
        counts.put("accounts", jdbc.query("SELECT COUNT(*) FROM accounts WHERE id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, a));
        return counts;
    }

    public boolean ledgerRowPresent(UUID a) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT TRUE FROM account_deletion_ledger WHERE old_account_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : null, a));
    }
}
