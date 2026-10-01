package com.dragosfotache.gtbackend.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Transaction boundary for account-scoped domain writes and committed sync publication.
 */
@Repository
public class AccountWriteRepository {
    private final JdbcTemplate jdbc;

    public AccountWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the account row until commit, serializing all writes for this owner. Domain edits,
     * revision changes and the returned batch must all be performed through this session.
     */
    @Transactional
    public <T> T inAccountWrite(UUID ownerId, Function<WriteSession, T> work) {
        Integer found = jdbc.query("SELECT id FROM accounts WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                rs -> rs.next() ? 1 : null, ownerId);
        if (found == null) throw new IllegalArgumentException("Unknown or disabled account");
        return work.apply(new WriteSession(ownerId));
    }

    /**
     * Ownership-scoped lookup; foreign and absent roots are indistinguishable.
     */
    public Optional<UUID> findWorkout(UUID ownerId, UUID workoutId) {
        return jdbc.query("SELECT id FROM workouts WHERE owner_id=? AND id=? AND deleted_at IS NULL",
                rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(), ownerId, workoutId);
    }

    public final class WriteSession {
        private final UUID ownerId;
        private boolean published;

        private WriteSession(UUID ownerId) {
            this.ownerId = ownerId;
        }

        /**
         * Advances a live owned root's revision; tombstone re-delete needs a separate explicit path.
         */
        public long nextRevision(String aggregateType, UUID aggregateId) {
            String table = switch (aggregateType) {
                case "workout" -> "workouts";
                case "routine" -> "routines";
                case "customExercise" -> "exercises";
                default -> throw new IllegalArgumentException("Unknown aggregate type");
            };
            Long revision = jdbc.query("UPDATE " + table + " SET revision=revision+1, updated_at=clock_timestamp() "
                            + "WHERE id=? AND owner_id=? AND deleted_at IS NULL RETURNING revision",
                    rs -> rs.next() ? rs.getLong(1) : null, aggregateId, ownerId);
            if (revision == null) throw new IllegalArgumentException("Unknown owned live root");
            return revision;
        }

        /**
         * Publishes complete change entries as one contiguous per-account committed batch.
         */
        public long publish(List<Change> changes) {
            if (published) throw new IllegalStateException("Only one batch per account write");
            if (changes.isEmpty()) throw new IllegalArgumentException("A published batch must contain changes");
            Long cursor = jdbc.query("UPDATE account_sync_state SET committed_cursor=committed_cursor+1 "
                            + "WHERE account_id=? RETURNING committed_cursor",
                    rs -> rs.next() ? rs.getLong(1) : null, ownerId);
            if (cursor == null) throw new IllegalStateException("Missing account cursor state");
            UUID batchId = UUID.randomUUID();
            jdbc.update("INSERT INTO sync_change_batches(account_id,cursor,batch_id) VALUES (?,?,?)", ownerId, cursor, batchId);
            for (int i = 0; i < changes.size(); i++) {
                Change c = changes.get(i);
                jdbc.update("INSERT INTO sync_changes(account_id,cursor,position,aggregate_type,aggregate_id,operation,revision,payload,expires_at) "
                                + "VALUES (?,?,?,?,?,?,?,?::jsonb,clock_timestamp()+interval '90 days')",
                        ownerId, cursor, i, c.aggregateType(), c.aggregateId(), c.operation(), c.revision(), c.payloadJson());
            }
            published = true;
            return cursor;
        }
    }

    public record Change(String aggregateType, UUID aggregateId, String operation, long revision, String payloadJson) {
        public Change {
            if (!List.of("workout", "routine", "customExercise").contains(aggregateType))
                throw new IllegalArgumentException("Unknown aggregate type");
            if (!List.of("upsert", "delete").contains(operation) || revision <= 0)
                throw new IllegalArgumentException("Invalid change");
            if ((operation.equals("upsert")) == (payloadJson == null))
                throw new IllegalArgumentException("Payload does not match operation");
        }
    }
}
