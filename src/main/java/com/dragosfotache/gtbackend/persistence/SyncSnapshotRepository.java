package com.dragosfotache.gtbackend.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Bounded SQL adapter for durable immutable snapshot headers and canonical records. */
@Repository
public class SyncSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public SyncSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads a bounded keyset of root IDs in the contract's canonical UUID-string order. */
    public List<UUID> nextRootIds(UUID accountId, RootType type, UUID afterId, int count) {
        String table = switch (type) {
            case CUSTOM_EXERCISE -> "exercises";
            case ROUTINE -> "routines";
            case WORKOUT -> "workouts";
        };
        String ownerFilter = type == RootType.CUSTOM_EXERCISE ? "owner_id=? AND is_user_created=true" : "owner_id=?";
        String sql = "select id from " + table + " where " + ownerFilter + " and deleted_at is null "
                + "and (cast(? as text) is null or (id::text collate \"C\") > (cast(? as text) collate \"C\")) "
                + "order by id::text collate \"C\" limit ?";
        String after = afterId == null ? null : afterId.toString();
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), accountId, after, after, count);
    }

    public void create(UUID accountId, UUID snapshotId, String baselineCursor, java.time.Instant createdAt,
                       java.time.Instant expiresAt) {
        jdbc.update("insert into sync_snapshots(account_id,snapshot_id,baseline_cursor,created_at,expires_at,record_count) "
                        + "values(?,?,?,?,?,0)", accountId, snapshotId, baselineCursor,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(expiresAt));
    }

    public void insertRecord(UUID accountId, UUID snapshotId, long ordinal, String type, UUID aggregateId,
                             Object payload) {
        try {
            jdbc.update("insert into sync_snapshot_records(account_id,snapshot_id,ordinal,aggregate_type,aggregate_id,payload) "
                            + "values(?,?,?,?,?,?::jsonb)", accountId, snapshotId, ordinal, type, aggregateId,
                    json.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("could not persist frozen snapshot record", e);
        }
    }

    public void complete(UUID accountId, UUID snapshotId, long recordCount) {
        int updated = jdbc.update("update sync_snapshots set record_count=? where account_id=? and snapshot_id=?",
                recordCount, accountId, snapshotId);
        if (updated != 1) throw new CorruptSnapshot();
    }

    public Header findHeader(UUID accountId, UUID snapshotId) {
        List<Header> rows = jdbc.query("select baseline_cursor,created_at,expires_at,record_count from sync_snapshots "
                        + "where account_id=? and snapshot_id=?",
                (rs, row) -> new Header(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(), rs.getLong(4)), accountId, snapshotId);
        if (rows.size() > 1) throw new CorruptSnapshot();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Returns a complete requested page or fails closed on missing, malformed, or misordered data. */
    public List<FrozenRecord> readPage(UUID accountId, UUID snapshotId, long offset, int count,
                                       long expectedCount) {
        long remaining = expectedCount - offset;
        if (offset < 0 || remaining < 0) throw new CorruptSnapshot();
        int expectedRows = (int) Math.min((long) count, remaining);
        List<FrozenRecord> rows = jdbc.query("select ordinal,aggregate_type,aggregate_id,payload::text "
                        + "from sync_snapshot_records where account_id=? and snapshot_id=? and ordinal>=? "
                        + "order by ordinal limit ?",
                (rs, row) -> decode(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)),
                accountId, snapshotId, offset, expectedRows);
        if (rows.size() != expectedRows) throw new CorruptSnapshot();
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).ordinal() != offset + i) throw new CorruptSnapshot();
        return rows;
    }

    private FrozenRecord decode(long ordinal, String type, UUID aggregateId, String raw) {
        try {
            JsonNode node = json.readTree(raw);
            if (!node.isObject()
                    || !type.equals(node.path("aggregateType").asText())
                    || !aggregateId.toString().equals(node.path("aggregateId").asText())
                    || !"upsert".equals(node.path("operation").asText())
                    || !node.path("revision").isIntegralNumber()
                    || node.path("revision").longValue() < 1
                    || !node.path("record").isObject()
                    || !aggregateId.toString().equals(node.path("record").path("id").asText())
                    || !node.path("record").path("revision").isIntegralNumber()
                    || node.path("record").path("revision").longValue() != node.path("revision").longValue())
                throw new CorruptSnapshot();
            @SuppressWarnings("unchecked")
            var value = json.treeToValue(node, java.util.Map.class);
            return new FrozenRecord(ordinal, type, aggregateId, value);
        } catch (CorruptSnapshot e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptSnapshot();
        }
    }

    public List<ExpiredSnapshot> lockExpired(java.time.Instant cutoff, int budget) {
        return jdbc.query("select account_id,snapshot_id from sync_snapshots where expires_at<=? "
                        + "order by expires_at,account_id,snapshot_id limit ? for update skip locked",
                (rs, row) -> new ExpiredSnapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                java.sql.Timestamp.from(cutoff), budget);
    }

    public void delete(ExpiredSnapshot snapshot, java.time.Instant cutoff) {
        jdbc.update("delete from sync_snapshots where account_id=? and snapshot_id=? and expires_at<=?",
                snapshot.accountId(), snapshot.snapshotId(), java.sql.Timestamp.from(cutoff));
    }

    public enum RootType {
        CUSTOM_EXERCISE("customExercise"), ROUTINE("routine"), WORKOUT("workout");
        private final String wire;
        RootType(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public record Header(String baselineCursor, java.time.Instant createdAt, java.time.Instant expiresAt,
                         long recordCount) { }
    public record FrozenRecord(long ordinal, String type, UUID aggregateId, java.util.Map<String, Object> value) { }
    public record ExpiredSnapshot(UUID accountId, UUID snapshotId) { }

    public static final class CorruptSnapshot extends RuntimeException { }
}
