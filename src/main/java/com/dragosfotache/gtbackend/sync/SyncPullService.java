package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** Reads complete retained change batches from one authorized PostgreSQL snapshot. */
@Service
public class SyncPullService {
    private static final int REGISTRATION_ATTEMPTS = 4;
    private final JdbcTemplate jdbc;
    private final SessionAuthorization authorization;
    private final SyncCursorRegistry cursors;
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate readTransaction;

    public SyncPullService(JdbcTemplate jdbc, SessionAuthorization authorization, SyncCursorRegistry cursors,
                           PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.cursors = cursors;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Map<String, Object> pull(String bearerToken, String suppliedCursor, int limit) {
        for (int attempt = 0; attempt < REGISTRATION_ATTEMPTS; attempt++) {
            try {
                return readTransaction.execute(status -> readSnapshot(bearerToken, suppliedCursor, limit));
            } catch (SyncCursorRegistry.ConcurrentRegistration race) {
                // The winner may be outside this transaction's RR snapshot. A fresh transaction
                // observes its stable token; retry only this metadata-registration race.
            } catch (RuntimeException failure) {
                if (!isSerializationConflict(failure)) throw failure;
                // PostgreSQL may report the concurrent RR unique conflict as 40001 rather than
                // completing ON CONFLICT DO NOTHING. The aborted transaction is discarded here.
            }
        }
        throw new TemporarilyUnavailable();
    }

    private Map<String, Object> readSnapshot(String bearerToken, String suppliedCursor, int limit) {
        UUID accountId = authorization.requireLiveAccessForRead(bearerToken);
        SyncState state = jdbc.query("select committed_cursor,pruned_through from account_sync_state where account_id=?",
                rs -> rs.next() ? new SyncState(rs.getLong(1), rs.getLong(2)) : null, accountId);
        if (state == null || state.prunedThrough() < 0 || state.committed() < state.prunedThrough())
            throw new CorruptHistory();

        long boundary;
        if (suppliedCursor == null) {
            boundary = state.prunedThrough();
        } else {
            OptionalLong resolved = cursors.resolve(accountId, suppliedCursor);
            if (resolved.isEmpty()) throw new InvalidCursor();
            boundary = resolved.getAsLong();
        }
        if (boundary < state.prunedThrough()) throw new CursorExpired();
        if (boundary > state.committed()) throw new CorruptHistory();

        List<StoredBatch> retained = readContiguousBatches(accountId, boundary, state.committed(), limit + 1);
        boolean hasMore = retained.size() > limit;
        List<Map<String, Object>> batches = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, retained.size()); i++) {
            StoredBatch stored = retained.get(i);
            batches.add(wireBatch(accountId, stored));
        }

        String nextCursor;
        if (batches.isEmpty()) {
            nextCursor = suppliedCursor == null ? cursors.issue(accountId, boundary) : suppliedCursor;
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> lastBatch = batches.get(batches.size() - 1);
            nextCursor = (String) lastBatch.get("cursor");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batches", batches);
        result.put("nextCursor", nextCursor);
        result.put("hasMore", hasMore);
        return result;
    }

    /**
     * Reads at most one extra batch to determine exact terminality. Every expected cursor in that
     * window and every position within each batch must be present; SQL ordering never hides holes.
     */
    private List<StoredBatch> readContiguousBatches(UUID accountId, long boundary, long committed, int count) {
        if (boundary == committed) return List.of();
        long window = Math.min((long) count, committed - boundary);
        long through = boundary + window;
        List<StoredBatch> batches = new ArrayList<>();
        List<Map<String, Object>> changes = null;
        long expectedCursor = boundary + 1;
        long currentCursor = -1;
        UUID batchId = null;
        int expectedPosition = 0;
        List<StoredRow> rows = jdbc.query("select b.cursor,b.batch_id,c.position,c.aggregate_type,c.aggregate_id,"
                        + "c.operation,c.revision,c.payload::text from sync_change_batches b "
                        + "left join sync_changes c on c.account_id=b.account_id and c.cursor=b.cursor "
                        + "where b.account_id=? and b.cursor>? and b.cursor<=? order by b.cursor,c.position",
                (rs, row) -> new StoredRow(rs.getLong(1), rs.getObject(2, UUID.class),
                        (Integer) rs.getObject(3), rs.getString(4), rs.getObject(5, UUID.class),
                        rs.getString(6), (Long) rs.getObject(7), rs.getString(8)),
                accountId, boundary, through);

        for (StoredRow row : rows) {
            if (row.cursor() != currentCursor) {
                if (currentCursor != -1) batches.add(new StoredBatch(currentCursor, batchId, changes));
                if (row.cursor() != expectedCursor) throw new CorruptHistory();
                currentCursor = row.cursor();
                expectedCursor++;
                batchId = row.batchId();
                changes = new ArrayList<>();
                expectedPosition = 0;
            } else if (!row.batchId().equals(batchId)) {
                throw new CorruptHistory();
            }
            if (row.position() == null || row.position() != expectedPosition++) throw new CorruptHistory();
            changes.add(readChange(row));
        }
        if (currentCursor != -1) batches.add(new StoredBatch(currentCursor, batchId, changes));
        if (expectedCursor != through + 1) throw new CorruptHistory();
        return batches;
    }

    private Map<String, Object> readChange(StoredRow row) {
        if (row.aggregateType() == null || row.aggregateId() == null || row.revision() == null
                || row.revision() <= 0 || !List.of("workout", "routine", "customExercise").contains(row.aggregateType()))
            throw new CorruptHistory();
        if ("delete".equals(row.operation())) {
            if (row.payload() != null) throw new CorruptHistory();
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("aggregateType", row.aggregateType());
            change.put("aggregateId", row.aggregateId().toString());
            change.put("operation", "delete");
            change.put("revision", row.revision());
            return change;
        }
        if (!"upsert".equals(row.operation()) || row.payload() == null) throw new CorruptHistory();
        try {
            JsonNode stored = json.readTree(row.payload());
            if (!stored.isObject()
                    || !row.aggregateType().equals(stored.path("aggregateType").asText())
                    || !row.aggregateId().toString().equals(stored.path("aggregateId").asText())
                    || !"upsert".equals(stored.path("operation").asText())
                    || !stored.path("revision").isIntegralNumber()
                    || stored.path("revision").longValue() != row.revision()
                    || !stored.path("record").isObject()
                    || !row.aggregateId().toString().equals(stored.path("record").path("id").asText())
                    || !stored.path("record").path("revision").isIntegralNumber()
                    || stored.path("record").path("revision").longValue() != row.revision())
                throw new CorruptHistory();
            @SuppressWarnings("unchecked")
            Map<String, Object> change = json.treeToValue(stored, Map.class);
            return change;
        } catch (CorruptHistory e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptHistory();
        }
    }

    private Map<String, Object> wireBatch(UUID accountId, StoredBatch stored) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", stored.batchId().toString());
        batch.put("cursor", cursors.issue(accountId, stored.cursor()));
        batch.put("changes", stored.changes());
        return batch;
    }

    private boolean isSerializationConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState())) return true;
        return false;
    }

    private record SyncState(long committed, long prunedThrough) { }
    private record StoredRow(long cursor, UUID batchId, Integer position, String aggregateType,
                             UUID aggregateId, String operation, Long revision, String payload) { }
    private record StoredBatch(long cursor, UUID batchId, List<Map<String, Object>> changes) { }

    public static final class InvalidCursor extends RuntimeException { }
    public static final class CursorExpired extends RuntimeException { }
    public static final class CorruptHistory extends RuntimeException { }
    public static final class TemporarilyUnavailable extends RuntimeException { }
}
