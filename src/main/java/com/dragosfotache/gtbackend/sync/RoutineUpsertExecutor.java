package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.domain.AggregateValidator;
import com.dragosfotache.gtbackend.resource.CanonicalResourceMapper;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.*;
import com.dragosfotache.gtbackend.persistence.AccountWriteRepository;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Savepoint;
import java.util.*;

/**
 * Ordered, transaction-fenced sync execution for the approved routine.upsert slice only.
 */
@Service
public class RoutineUpsertExecutor {
    private final JdbcTemplate jdbc;
    private final SessionAuthorization auth;
    private final AccountWriteRepository writes;
    private final CanonicalAggregateRepository aggregates;
    private final SyncCursorRegistry cursors;
    private final ObjectMapper json = new ObjectMapper();
    private final CanonicalResourceMapper resourceMapper = new CanonicalResourceMapper();
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public RoutineUpsertExecutor(JdbcTemplate jdbc, SessionAuthorization auth, AccountWriteRepository writes,
                                 CanonicalAggregateRepository aggregates, SyncCursorRegistry cursors,
                                 PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.writes = writes;
        this.aggregates = aggregates;
        this.cursors = cursors;
        this.transactions = new TransactionTemplate(tx);
    }

    /** Kept for existing isolated executor restart checks; production uses the shared registry bean. */
    public RoutineUpsertExecutor(JdbcTemplate jdbc, SessionAuthorization auth, AccountWriteRepository writes,
                                 CanonicalAggregateRepository aggregates, PlatformTransactionManager tx) {
        this(jdbc, auth, writes, aggregates, new SyncCursorRegistry(jdbc), tx);
    }

    public List<Map<String, Object>> push(String token, List<PushPreflight.Mutation> mutations) {

        // Unsupported, structurally valid work is never claimed or partially preceded by writes.
        if (mutations.stream().anyMatch(m -> !Set.of("routine.upsert", "routine.save", "routine.deleteExercise", "routine.deleteSet", "customExercise.upsert", "workout.start", "workout.upsert", "workout.finish", "workout.performAgain", "routine.startWorkout", "workout.saveAsRoutine", "workout.addExercises", "workout.replaceExercise", "workout.deleteExercise", "workout.deleteSet", "aggregate.delete", "aggregate.import").contains(type(m))))
            throw new TemporarilyUnavailable();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var m : mutations)
            result.add(transactions.execute(status -> {
                UUID owner = auth.requireLiveAccess(token);
                return writes.inAccountWrite(owner, session -> execute(owner, m));
            }));
        return result;
    }

    private String type(PushPreflight.Mutation m) {
        try {
            return json.readTree(m.raw()).path("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> execute(UUID owner, PushPreflight.Mutation mutation) {
        UUID id = UUID.fromString(mutation.id());
        byte[] raw = mutation.raw();
        Previous previous = jdbc.query("select request_bytes,outcome from processed_mutations where owner_id=? and mutation_id=? for update",
                rs -> rs.next() ? new Previous(rs.getBytes(1), rs.getString(2)) : null, owner, id);
        if (previous != null) {
            if (previous.raw == null || !Arrays.equals(previous.raw, raw))
                return outcome(id, "rejected", "mutation_id_reused", null);
            try {
                Map<String, Object> replay = json.readValue(previous.outcome, Map.class);
                replay.put("status", "duplicate");
                return replay;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        DomainCommandResult result = executeDomainCommandInAccountWrite(owner, mutation);
        Map<String, Object> syncOutcome = switch (result) {
            case DomainCommandResult.Accepted accepted -> outcome(id, "accepted", null, accepted.batch());
            case DomainCommandResult.Rejected rejected -> outcome(id, "rejected", rejected.errorCode(), null);
        };
        insertEvidence(owner, id, raw, syncOutcome);
        return syncOutcome;
    }

    public DomainCommandResult executeDomainCommandInAccountWrite(UUID owner, PushPreflight.Mutation mutation) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("domain command requires the account write transaction");
        return applyCommand(owner, mutation);
    }

    public sealed interface DomainCommandResult {
        record Accepted(Map<String, Object> batch) implements DomainCommandResult {
        }

        record Rejected(String errorCode) implements DomainCommandResult {
        }
    }

    private DomainCommandResult applyCommand(UUID owner, PushPreflight.Mutation mutation) {
        JsonNode n;
        try {
            n = json.readTree(mutation.raw());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String kind = type(mutation);
        Routine r = null;
        Workout start = null;
        Workout workoutReplacement = null;
        boolean performAgain = kind.equals("workout.performAgain");
        boolean startFromRoutine = kind.equals("routine.startWorkout");
        boolean saveAsRoutine = kind.equals("workout.saveAsRoutine");
        Routine createdRoutine = null;
        boolean addExercises = kind.equals("workout.addExercises");
        boolean finishing = kind.equals("workout.finish");
        boolean deleting = kind.equals("aggregate.delete");
        boolean importing = kind.equals("aggregate.import");
        String importType = n.path("aggregateType").asText();
        CustomExercise importedCustom = null;
        long deletedRevision = 0;
        java.time.Instant finishedAt = null;
        CanonicalAggregateRepository.StartWorkoutResult startResult = null;
        boolean createRoutine = false;
        UUID target = UUID.fromString(n.path("aggregateId").asText());
        String code = null;
        if (kind.equals("workout.start")) {
            JsonNode p = n.path("payload");
            start = new Workout(target, p.path("name").asText(), WorkoutStatus.ACTIVE, java.time.OffsetDateTime.parse(p.path("startedAt").asText()).toInstant(), null, 0, List.of());
        }
        if (saveAsRoutine) {
            JsonNode p = n.path("payload");
            UUID sourceId = UUID.fromString(p.path("sourceWorkoutId").asText());
            String state = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, sourceId, owner);
            if (state == null) code = "not_found";
            else if (!state.equals("finished")) code = "invalid_state";
            else {
                Workout source = aggregates.readWorkout(owner, sourceId).root();
                JsonNode bindings = p.path("childIds");
                String name = p.path("name").asText();
                if (com.dragosfotache.gtbackend.domain.AggregateValidator.blank(name)) code = "validation_error";
                else if (!matchesWorkoutBindings(source, bindings)) code = "source_shape_changed";
                else if (jdbc.query("select 1 from root_id_reservations where aggregate_type='routine' and aggregate_id=?", rs -> rs.next() ? 1 : null, target) != null
                        || jdbc.query("select 1 from child_id_reservations where family='routine' and child_id=?", rs -> rs.next() ? 1 : null, target) != null)
                    code = "id_collision";
                else {
                    for (JsonNode e : bindings) {
                        if (jdbc.query("select 1 from child_id_reservations where family='routine' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(e.path("id").asText())) != null)
                            code = "id_collision";
                        for (JsonNode s : e.path("sets"))
                            if (jdbc.query("select 1 from child_id_reservations where family='routine' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(s.path("id").asText())) != null)
                                code = "id_collision";
                    }
                    if (code == null) createdRoutine = routineFromWorkout(source, target, name, bindings);
                }
            }
        }
        if (performAgain || startFromRoutine) {
            JsonNode p = n.path("payload");
            if (startFromRoutine) {
                UUID sourceId = UUID.fromString(p.path("sourceRoutineId").asText());
                String sourceState = jdbc.query("select is_draft::text from routines where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, sourceId, owner);
                if (sourceState == null) code = "not_found";
                else if (Boolean.parseBoolean(sourceState)) code = "invalid_state";
                else {
                    var source = aggregates.readRoutine(owner, sourceId).root();
                    if (com.dragosfotache.gtbackend.domain.AggregateValidator.blank(source.name()) || source.exercises().isEmpty())
                        code = "invalid_state";
                    else {
                        JsonNode bindings = p.path("childIds");
                        if (!matchesRoutineBindings(source, bindings)) code = "source_shape_changed";
                        else if (jdbc.query("select 1 from root_id_reservations where aggregate_type='workout' and aggregate_id=?", rs -> rs.next() ? 1 : null, target) != null
                                || jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, target) != null)
                            code = "id_collision";
                        else {
                            for (JsonNode e : bindings) {
                                if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(e.path("id").asText())) != null)
                                    code = "id_collision";
                                for (JsonNode s : e.path("sets"))
                                    if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(s.path("id").asText())) != null)
                                        code = "id_collision";
                            }
                            if (code == null)
                                start = workoutFromRoutine(source, target, p.path("startedAt").asText(), bindings);
                        }
                    }
                }
            } else {
                UUID sourceId = UUID.fromString(p.path("sourceWorkoutId").asText());
                String sourceState = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, sourceId, owner);
                if (sourceState == null) code = "not_found";
                else if (!sourceState.equals("finished")) code = "invalid_state";
                else {
                    WorkoutReadHolder source = new WorkoutReadHolder(aggregates.readWorkout(owner, sourceId).root());
                    JsonNode bindings = p.path("childIds");
                    if (!matchesWorkoutBindings(source.value, bindings)) code = "source_shape_changed";
                    else if (jdbc.query("select 1 from root_id_reservations where aggregate_type='workout' and aggregate_id=?", rs -> rs.next() ? 1 : null, target) != null
                            || jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, target) != null)
                        code = "id_collision";
                    else {
                        for (JsonNode e : bindings) {
                            UUID entryId = UUID.fromString(e.path("id").asText());
                            if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, entryId) != null)
                                code = "id_collision";
                            for (JsonNode s : e.path("sets"))
                                if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(s.path("id").asText())) != null)
                                    code = "id_collision";
                        }
                        if (code == null)
                            start = cloneWorkout(source.value, target, p.path("startedAt").asText(), bindings);
                    }
                }
            }
        }
        if (addExercises) {
            String state = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, target, owner);
            if (state == null) code = "not_found";
            else if (!state.equals("active")) code = "invalid_state";
            else {
                Workout current = aggregates.readWorkout(owner, target).root();
                for (JsonNode addition : n.path("payload").path("additions")) {
                    UUID exerciseId = UUID.fromString(addition.path("exerciseId").asText());
                    Integer visible = jdbc.query("select 1 from exercises where id=? and deleted_at is null and (owner_id is null or owner_id=?)", rs -> rs.next() ? 1 : null, exerciseId, owner);
                    if (visible == null) {
                        code = "invalid_reference";
                        break;
                    }
                }
                if (code == null) {
                    List<WorkoutExercise> appended = new ArrayList<>(current.exercises());
                    int pos = appended.size();
                    for (JsonNode addition : n.path("payload").path("additions")) {
                        UUID exerciseId = UUID.fromString(addition.path("exerciseId").asText());
                        HistoryTemplate history = latestCompletedHistory(owner, exerciseId);
                        JsonNode ids = addition.path("setIds");
                        int expected = history == null ? 1 : history.sets().size();
                        if (ids.size() != expected) {
                            code = "history_shape_changed";
                            break;
                        }
                        UUID entryId = UUID.fromString(addition.path("id").asText());
                        if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, entryId) != null) {
                            code = "id_collision";
                            break;
                        }
                        for (JsonNode setId : ids)
                            if (jdbc.query("select 1 from child_id_reservations where family='workout' and child_id=?", rs -> rs.next() ? 1 : null, UUID.fromString(setId.asText())) != null)
                                code = "id_collision";
                        if (code != null) break;
                        List<SetValue> sets = new ArrayList<>();
                        if (history == null)
                            sets.add(new SetValue(UUID.fromString(ids.get(0).asText()), 0, null, null, false, null));
                        else for (int i = 0; i < history.sets().size(); i++) {
                            SetValue old = history.sets().get(i);
                            sets.add(new SetValue(UUID.fromString(ids.get(i).asText()), i, old.repetitions(), old.weightKg(), false, null));
                        }
                        appended.add(new WorkoutExercise(entryId, exerciseId, pos++, "", history == null ? 30 : history.restDurationSeconds(), sets));
                    }
                    if (code == null)
                        workoutReplacement = new Workout(current.id(), current.name(), WorkoutStatus.ACTIVE, current.startedAt(), null, 0, appended);
                }
            }
        }
        if (kind.equals("workout.upsert")) {
            workoutReplacement = decodeWorkout(n.path("payload"));
            String state = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? rs.getString(1) : null, target, owner);
            if (state == null) code = "not_found";
            else if (!state.equals("active")) code = "invalid_state";
            try {
                AggregateValidator.workout(workoutReplacement, false);
            } catch (IllegalArgumentException e) {
                code = "validation_error";
            }
        }
        if (finishing)
            finishedAt = java.time.OffsetDateTime.parse(n.path("payload").path("finishedAt").asText()).toInstant();
        if (kind.equals("routine.upsert")) {
            r = decode(n.path("payload"));
            String state = jdbc.query("select is_draft::text from routines where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? rs.getString(1) : null, r.id(), owner);
            createRoutine = state == null;
            if (state == null && !r.draft()) code = "invalid_state";
            if (state != null && Boolean.parseBoolean(state) != r.draft()) code = "invalid_state";
            if (code == null) try {
                AggregateValidator.routine(r, state == null);
            } catch (IllegalArgumentException e) {
                code = "validation_error";
            }
        }
        if (finishing) {
            String state = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? rs.getString(1) : null, target, owner);
            if (state == null) code = "not_found";
            else if (!state.equals("active")) code = "invalid_state";
        }
        boolean childCommand = Set.of("workout.replaceExercise", "workout.deleteExercise", "workout.deleteSet", "routine.deleteExercise", "routine.deleteSet").contains(kind);
        boolean routineSave = kind.equals("routine.save");
        String saveName = null;
        if (routineSave) {
            saveName = n.path("payload").path("name").asText();
            Integer exists = jdbc.query("select 1 from routines where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? 1 : null, target, owner);
            if (exists == null) code = "not_found";
            else if (com.dragosfotache.gtbackend.domain.AggregateValidator.blank(saveName)
                    || jdbc.queryForObject("select count(*) from routine_exercises where routine_id=?", Integer.class, target) == 0)
                code = "validation_error";
        }
        CustomExercise custom = null;
        if (kind.equals("customExercise.upsert")) {
            custom = decodeCustom(n.path("payload"));
            Integer existing = jdbc.query("select 1 from exercises where id=? and owner_id=? and is_user_created=true and deleted_at is null", rs -> rs.next() ? 1 : null, target, owner);
            Integer bundled = jdbc.query("select 1 from exercises where id=? and owner_id is null", rs -> rs.next() ? 1 : null, target);
            Integer anyId = jdbc.query("select 1 from exercises where id=?", rs -> rs.next() ? 1 : null, target);
            if (existing == null && bundled != null) code = "read_only";
            else if (existing == null && anyId != null) code = "id_collision";
            try {
                AggregateValidator.customExercise(custom);
            } catch (IllegalArgumentException e) {
                code = "validation_error";
            }
        }
        if (deleting) code = validateDelete(owner, importType, target);
        if (importing && code == null) {
            JsonNode record = n.path("payload").path("record");
            if (importType.equals("workout")) {
                workoutReplacement = decodeImportedWorkout(record);
                try {
                    AggregateValidator.workout(workoutReplacement, true);
                } catch (IllegalArgumentException e) {
                    code = "validation_error";
                }
                if (code == null && !freshRoot(owner, importType, target)) code = "id_collision";
            } else if (importType.equals("routine")) {
                r = decode(record);
                try {
                    AggregateValidator.routine(r, false);
                } catch (IllegalArgumentException e) {
                    code = "validation_error";
                }
                if (code == null && !freshRoot(owner, importType, target)) code = "id_collision";
            } else {
                importedCustom = decodeCustom(record);
                try {
                    AggregateValidator.customExercise(importedCustom);
                } catch (IllegalArgumentException e) {
                    code = "validation_error";
                }
                if (code == null && !freshRoot(owner, importType, target)) code = "id_collision";
            }
        }
        if (code == null) {
            java.sql.Connection connection = DataSourceUtils.getConnection(jdbc.getDataSource());
            Savepoint point = savepoint(connection);
            try {
                if (childCommand) {
                    code = executeChildCommand(owner, target, kind, n.path("payload"));
                    if (code != null) rollback(connection, point);
                    else release(connection, point);
                } else if (deleting) deletedRevision = deleteRoot(owner, importType, target);
                else if (importing) {
                    if (importType.equals("workout")) {
                        if (workoutReplacement.status() == WorkoutStatus.ACTIVE)
                            startResult = aggregates.importActiveWorkoutInLockedAccount(owner, workoutReplacement);
                        else aggregates.importWorkoutRowsInLockedAccount(owner, workoutReplacement);
                    } else if (importType.equals("routine")) aggregates.importRoutineRowsInLockedAccount(owner, r);
                    else aggregates.importCustomExerciseRowsInLockedAccount(owner, importedCustom);
                } else if (start != null) startResult = (performAgain || startFromRoutine)
                        ? aggregates.startWorkoutCloneRowsInLockedAccount(owner, start)
                        : aggregates.startWorkoutRowsInLockedAccount(owner, start);
                else if (workoutReplacement != null)
                    aggregates.replaceActiveWorkoutRowsInLockedAccount(owner, workoutReplacement);
                else if (finishing) aggregates.finishWorkoutInLockedAccount(owner, target, finishedAt);
                else if (createdRoutine != null)
                    aggregates.createSavedRoutineCloneInLockedAccount(owner, createdRoutine);
                else if (r != null) aggregates.replaceRoutineRowsInLockedAccount(owner, r, createRoutine);
                else if (routineSave)
                    jdbc.update("update routines set name=?,is_draft=false,revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=? and deleted_at is null", saveName, target, owner);
                else aggregates.replaceCustomExerciseRowsInLockedAccount(owner, custom);
                if (!childCommand) release(connection, point);
            } catch (CanonicalAggregateRepository.InvalidReferenceException e) {
                rollback(connection, point);
                code = "invalid_reference";
            } catch (CanonicalAggregateRepository.NoCompletedSetsException e) {
                rollback(connection, point);
                code = "validation_error";
            } catch (IllegalStateException e) {
                rollback(connection, point);
                code = "invalid_state";
            } catch (IllegalArgumentException e) {
                rollback(connection, point);
                code = "validation_error";
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                String domainCode = sqlDomainCode(e);
                if (domainCode == null) throw e;
                rollback(connection, point);
                code = domainCode;
            } finally {
                DataSourceUtils.releaseConnection(connection, jdbc.getDataSource());
            }
        }
        if (code != null) return new DomainCommandResult.Rejected(code);
        List<Map<String, Object>> changes = new ArrayList<>();
        if (deleting) {
            changes.add(Map.of("aggregateType", importType, "aggregateId", target.toString(), "operation", "delete", "revision", deletedRevision));
        } else if (importing && importType.equals("workout") && workoutReplacement.status() == WorkoutStatus.ACTIVE) {
            if (startResult.replacedId() != null)
                changes.add(Map.of("aggregateType", "workout", "aggregateId", startResult.replacedId().toString(), "operation", "delete", "revision", startResult.replacedRevision()));
            var read = aggregates.readWorkout(owner, target);
            changes.add(Map.of("aggregateType", "workout", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireWorkout(read)));
        } else if (importing && importType.equals("workout")) {
            var read = aggregates.readWorkout(owner, target);
            changes.add(Map.of("aggregateType", "workout", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireWorkout(read)));
        } else if (importing && importType.equals("routine")) {
            var read = aggregates.readRoutine(owner, target);
            changes.add(Map.of("aggregateType", "routine", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireRoutine(read)));
        } else if (importing) {
            ExerciseValue read = aggregates.readVisibleExercise(owner, target);
            changes.add(Map.of("aggregateType", "customExercise", "aggregateId", target.toString(), "operation", "upsert", "revision", read.revision(), "record", resourceMapper.exercise(read)));
        } else if (start != null) {
            if (startResult.replacedId() != null)
                changes.add(Map.of("aggregateType", "workout", "aggregateId", startResult.replacedId().toString(), "operation", "delete", "revision", startResult.replacedRevision()));
            var read = aggregates.readWorkout(owner, target);
            changes.add(Map.of("aggregateType", "workout", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireWorkout(read)));
        } else if (workoutReplacement != null || finishing || (kind.startsWith("workout.") && !saveAsRoutine)) {
            var read = aggregates.readWorkout(owner, target);
            changes.add(Map.of("aggregateType", "workout", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireWorkout(read)));
        } else if (kind.equals("customExercise.upsert")) {
            ExerciseValue read = aggregates.readVisibleExercise(owner, target);
            changes.add(Map.of("aggregateType", "customExercise", "aggregateId", target.toString(), "operation", "upsert", "revision", read.revision(), "record", resourceMapper.exercise(read)));
        } else {
            var read = aggregates.readRoutine(owner, target);
            changes.add(Map.of("aggregateType", "routine", "aggregateId", target.toString(), "operation", "upsert", "revision", read.root().revision(), "record", wireRoutine(read)));
        }
        try {
            long cursor = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, owner);
            long next = cursor + 1;
            String encoded = cursors.issue(owner, next);
            UUID batchId = UUID.randomUUID();
            jdbc.update("update account_sync_state set committed_cursor=? where account_id=?", next, owner);
            jdbc.update("insert into sync_change_batches(account_id,cursor,batch_id) values(?,?,?)", owner, next, batchId);
            for (int i = 0; i < changes.size(); i++) {
                Map<String, Object> change = changes.get(i);
                jdbc.update("insert into sync_changes(account_id,cursor,position,aggregate_type,aggregate_id,operation,revision,payload,expires_at) values(?,?,?,?,?,?,?,?::jsonb,clock_timestamp()+interval '90 days')", owner, next, i, change.get("aggregateType"), UUID.fromString((String) change.get("aggregateId")), change.get("operation"), change.get("revision"), change.containsKey("record") ? json(change) : null);
            }
            Map<String, Object> batch = Map.of("batchId", batchId.toString(), "cursor", encoded, "changes", changes);
            return new DomainCommandResult.Accepted(batch);
        } catch (Exception e) {
            throw new IllegalStateException("sync commit failed", e);
        }
    }

    private String validateDelete(UUID owner, String type, UUID id) {
        String table = type.equals("workout") ? "workouts" : type.equals("routine") ? "routines" : "exercises";
        if (type.equals("customExercise") && jdbc.query("select 1 from exercises where id=? and owner_id is null and is_user_created=false", rs -> rs.next() ? 1 : null, id) != null)
            return "read_only";
        Integer own = jdbc.query("select 1 from " + table + " where id=? and owner_id=? and " + (type.equals("customExercise") ? "is_user_created=true" : "true"), rs -> rs.next() ? 1 : null, id, owner);
        if (own == null) return "not_found";
        Integer live = jdbc.query("select 1 from " + table + " where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? 1 : null, id, owner);
        if (live == null) return null;
        if (type.equals("customExercise")) {
            Integer refs = jdbc.query("select 1 from workout_exercises e join workouts w on w.id=e.workout_id where e.exercise_id=? and w.owner_id=? and w.deleted_at is null union all select 1 from routine_exercises e join routines r on r.id=e.routine_id where e.exercise_id=? and r.owner_id=? and r.deleted_at is null limit 1", rs -> rs.next() ? 1 : null, id, owner, id, owner);
            if (refs != null) return "exercise_in_use";
        }
        return null;
    }

    private long deleteRoot(UUID owner, String type, UUID id) {
        String table = type.equals("workout") ? "workouts" : type.equals("routine") ? "routines" : "exercises";
        Long revision = jdbc.query("update " + table + " set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=? and deleted_at is null returning revision", rs -> rs.next() ? rs.getLong(1) : null, id, owner);
        if (revision == null)
            revision = jdbc.queryForObject("update " + table + " set revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=? returning revision", Long.class, id, owner);
        return revision;
    }

    private boolean freshRoot(UUID owner, String type, UUID id) {
        return jdbc.query("select 1 from root_id_reservations where aggregate_type=? and aggregate_id=?", rs -> !rs.next(), type, id);
    }

    private Workout decodeImportedWorkout(JsonNode p) {
        Workout active = decodeWorkout(p);
        JsonNode finishedNode = p.get("finishedAt");
        java.time.Instant finished = finishedNode == null || finishedNode.isNull() ? null : java.time.OffsetDateTime.parse(finishedNode.asText()).toInstant();
        return new Workout(active.id(), active.name(), p.path("status").asText().equals("finished") ? WorkoutStatus.FINISHED : WorkoutStatus.ACTIVE, active.startedAt(), finished, 0, active.exercises());
    }

    private record WorkoutReadHolder(Workout value) {
    }

    private boolean matchesWorkoutBindings(Workout source, JsonNode bindings) {
        if (!bindings.isArray() || bindings.size() != source.exercises().size()) return false;
        for (int i = 0; i < source.exercises().size(); i++) {
            WorkoutExercise e = source.exercises().get(i);
            JsonNode b = bindings.get(i);
            if (!b.path("sourceExerciseId").asText().equals(e.id().toString()) || !b.path("sets").isArray() || b.path("sets").size() != e.sets().size())
                return false;
            for (int j = 0; j < e.sets().size(); j++)
                if (!b.path("sets").get(j).path("sourceSetId").asText().equals(e.sets().get(j).id().toString()))
                    return false;
        }
        return true;
    }

    private Workout cloneWorkout(Workout source, UUID id, String startedAt, JsonNode bindings) {
        List<WorkoutExercise> exercises = new ArrayList<>();
        for (int i = 0; i < source.exercises().size(); i++) {
            WorkoutExercise old = source.exercises().get(i);
            JsonNode binding = bindings.get(i);
            List<SetValue> sets = new ArrayList<>();
            for (int j = 0; j < old.sets().size(); j++) {
                SetValue s = old.sets().get(j);
                sets.add(new SetValue(UUID.fromString(binding.path("sets").get(j).path("id").asText()), j, s.repetitions(), s.weightKg(), false, null));
            }
            exercises.add(new WorkoutExercise(UUID.fromString(binding.path("id").asText()), old.exerciseId(), i, old.notes(), old.restDurationSeconds(), sets));
        }
        return new Workout(id, source.name(), WorkoutStatus.ACTIVE, java.time.OffsetDateTime.parse(startedAt).toInstant(), null, 0, exercises);
    }

    private record HistoryTemplate(int restDurationSeconds, List<SetValue> sets) {
    }

    private HistoryTemplate latestCompletedHistory(UUID owner, UUID exerciseId) {
        record Occurrence(UUID id, int restDurationSeconds) {
        }
        Occurrence occurrence = jdbc.query("select e.id,e.rest_duration_seconds from workout_exercises e join workouts w on w.id=e.workout_id where w.owner_id=? and w.status='finished' and w.deleted_at is null and e.exercise_id=? and exists(select 1 from workout_sets s where s.workout_exercise_id=e.id and s.is_completed) order by w.finished_at desc,w.id desc,e.position asc limit 1",
                rs -> rs.next() ? new Occurrence(rs.getObject(1, UUID.class), rs.getInt(2)) : null, owner, exerciseId);
        if (occurrence == null) return null;
        List<SetValue> sets = jdbc.query("select id,position,repetitions,weight_kg,is_completed,rest_seconds from workout_sets where workout_exercise_id=? and is_completed order by position",
                (rs, row) -> new SetValue(rs.getObject(1, UUID.class), rs.getInt(2), (Integer) rs.getObject(3), (Double) rs.getObject(4), true, (Integer) rs.getObject(6)), occurrence.id());
        return new HistoryTemplate(occurrence.restDurationSeconds(), sets);
    }

    private Routine routineFromWorkout(Workout source, UUID id, String name, JsonNode bindings) {
        List<RoutineExercise> exercises = new ArrayList<>();
        for (int i = 0; i < source.exercises().size(); i++) {
            WorkoutExercise old = source.exercises().get(i);
            JsonNode binding = bindings.get(i);
            List<RoutineSet> sets = new ArrayList<>();
            for (int j = 0; j < old.sets().size(); j++) {
                SetValue s = old.sets().get(j);
                sets.add(new RoutineSet(UUID.fromString(binding.path("sets").get(j).path("id").asText()), j, s.repetitions(), s.weightKg()));
            }
            exercises.add(new RoutineExercise(UUID.fromString(binding.path("id").asText()), old.exerciseId(), i, old.restDurationSeconds(), sets));
        }
        return new Routine(id, name, false, 0, exercises);
    }

    private boolean matchesRoutineBindings(Routine source, JsonNode bindings) {
        if (!bindings.isArray() || bindings.size() != source.exercises().size()) return false;
        for (int i = 0; i < source.exercises().size(); i++) {
            RoutineExercise e = source.exercises().get(i);
            JsonNode b = bindings.get(i);
            if (!b.path("sourceExerciseId").asText().equals(e.id().toString()) || !b.path("sets").isArray() || b.path("sets").size() != e.sets().size())
                return false;
            for (int j = 0; j < e.sets().size(); j++)
                if (!b.path("sets").get(j).path("sourceSetId").asText().equals(e.sets().get(j).id().toString()))
                    return false;
        }
        return true;
    }

    private Workout workoutFromRoutine(Routine source, UUID id, String startedAt, JsonNode bindings) {
        List<WorkoutExercise> exercises = new ArrayList<>();
        for (int i = 0; i < source.exercises().size(); i++) {
            RoutineExercise old = source.exercises().get(i);
            JsonNode binding = bindings.get(i);
            List<SetValue> sets = new ArrayList<>();
            for (int j = 0; j < old.sets().size(); j++) {
                RoutineSet s = old.sets().get(j);
                sets.add(new SetValue(UUID.fromString(binding.path("sets").get(j).path("id").asText()), j, s.repetitions(), s.weightKg(), false, null));
            }
            exercises.add(new WorkoutExercise(UUID.fromString(binding.path("id").asText()), old.exerciseId(), i, "", old.restDurationSeconds(), sets));
        }
        return new Workout(id, source.name(), WorkoutStatus.ACTIVE, java.time.OffsetDateTime.parse(startedAt).toInstant(), null, 0, exercises);
    }

    /**
     * Applies one of slice 5d's child commands; caller owns account lock and savepoint.
     */
    private String executeChildCommand(UUID owner, UUID root, String kind, JsonNode payload) {
        boolean workout = kind.startsWith("workout.");
        String roots = workout ? "workouts" : "routines";
        String state = jdbc.query("select " + (workout ? "status" : "'routine'") + " from " + roots + " where id=? and owner_id=? and deleted_at is null for update",
                rs -> rs.next() ? rs.getString(1) : null, root, owner);
        if (state == null) return "not_found";
        if (workout && !state.equals("active")) return "invalid_state";
        String child = payload.path(workout ? (kind.endsWith("deleteSet") ? "workoutSetId" : "workoutExerciseId") : (kind.endsWith("deleteSet") ? "routineSetId" : "routineExerciseId")).asText();
        UUID childId = UUID.fromString(child);
        if (kind.endsWith("replaceExercise")) {
            Integer childExists = jdbc.query("select 1 from workout_exercises where id=? and workout_id=? for update", rs -> rs.next() ? 1 : null, childId, root);
            if (childExists == null) return "not_found";
            UUID exercise = UUID.fromString(payload.path("exerciseId").asText());
            Integer visible = jdbc.query("select 1 from exercises where id=? and deleted_at is null and (owner_id is null or owner_id=?)", rs -> rs.next() ? 1 : null, exercise, owner);
            if (visible == null) return "invalid_reference";
            jdbc.update("update workout_exercises set exercise_id=? where id=? and workout_id=?", exercise, childId, root);
        } else if (kind.endsWith("deleteExercise")) {
            String entries = workout ? "workout_exercises" : "routine_exercises";
            String sets = workout ? "workout_sets" : "routine_sets";
            String parent = workout ? "workout_exercise_id" : "routine_exercise_id";
            jdbc.update("delete from " + sets + " where " + parent + " in (select id from " + entries + " where id=? and " + (workout ? "workout_id" : "routine_id") + "=?)", childId, root);
            int deleted = jdbc.update("delete from " + entries + " where id=? and " + (workout ? "workout_id" : "routine_id") + "=?", childId, root);
            if (deleted == 0) return "not_found";
            renumber(entries, workout ? "workout_id" : "routine_id", root);
        } else {
            String sets = workout ? "workout_sets" : "routine_sets";
            String entries = workout ? "workout_exercises" : "routine_exercises";
            String parent = workout ? "workout_exercise_id" : "routine_exercise_id";
            String rootColumn = workout ? "workout_id" : "routine_id";
            UUID parentId = jdbc.query("select e.id from " + entries + " e join " + sets + " s on s." + parent + "=e.id where s.id=? and e." + rootColumn + "=?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, childId, root);
            if (parentId == null) return "not_found";
            jdbc.update("delete from " + sets + " where id=?", childId);
            renumber(sets, parent, parentId);
        }
        jdbc.update("update " + roots + " set revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=?", root, owner);
        return null;
    }

    private void renumber(String table, String parentColumn, UUID parent) {
        Integer offset = jdbc.queryForObject("select coalesce(max(position),0)+1 from " + table + " where " + parentColumn + "=?", Integer.class, parent);
        jdbc.update("update " + table + " set position=position+? where " + parentColumn + "=?", offset, parent);
        jdbc.update("with ranked as (select id,row_number() over(order by position)-1 p from " + table + " where " + parentColumn + "=?) update " + table + " t set position=ranked.p from ranked where t.id=ranked.id", parent);
    }

    private Savepoint savepoint(java.sql.Connection c) {
        try {
            return c.setSavepoint();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void rollback(java.sql.Connection c, Savepoint p) {
        try {
            c.rollback(p);
            c.releaseSavepoint(p);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void release(java.sql.Connection c, Savepoint p) {
        try {
            c.releaseSavepoint(p);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID rId(JsonNode n) {
        return UUID.fromString(n.path("aggregateId").asText());
    }

    private Routine decode(JsonNode p) {
        try {
            UUID root = UUID.fromString(p.path("id").asText());
            List<RoutineExercise> es = new ArrayList<>();
            for (JsonNode e : p.path("exercises")) {
                List<RoutineSet> ss = new ArrayList<>();
                for (JsonNode s : e.path("sets"))
                    ss.add(new RoutineSet(UUID.fromString(s.path("id").asText()), s.path("position").asInt(), nullableInt(s.get("repetitions")), nullableDouble(s.get("weightKg"))));
                es.add(new RoutineExercise(UUID.fromString(e.path("id").asText()), UUID.fromString(e.path("exerciseId").asText()), e.path("position").asInt(), e.path("restDurationSeconds").asInt(), ss));
            }
            return new Routine(root, p.path("name").asText(), p.path("isDraft").asBoolean(), 0, es);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid routine");
        }
    }

    private Workout decodeWorkout(JsonNode p) {
        try {
            List<WorkoutExercise> exercises = new ArrayList<>();
            for (JsonNode e : p.path("exercises")) {
                List<SetValue> sets = new ArrayList<>();
                for (JsonNode s : e.path("sets"))
                    sets.add(new SetValue(UUID.fromString(s.path("id").asText()), s.path("position").asInt(), nullableInt(s.get("repetitions")), nullableDouble(s.get("weightKg")), s.path("isCompleted").asBoolean(), nullableInt(s.get("restSeconds"))));
                exercises.add(new WorkoutExercise(UUID.fromString(e.path("id").asText()), UUID.fromString(e.path("exerciseId").asText()), e.path("position").asInt(), e.path("notes").asText(), e.path("restDurationSeconds").asInt(), sets));
            }
            return new Workout(UUID.fromString(p.path("id").asText()), p.path("name").asText(), WorkoutStatus.ACTIVE, java.time.OffsetDateTime.parse(p.path("startedAt").asText()).toInstant(), null, 0, exercises);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid workout");
        }
    }

    private CustomExercise decodeCustom(JsonNode p) {
        try {
            List<Muscle> muscles = new ArrayList<>();
            JsonNode primary = p.get("primaryMuscleGroupId");
            if (primary != null && !primary.isNull())
                muscles.add(new Muscle(UUID.fromString(primary.asText()), "primary"));
            if (p.has("secondaryMuscleGroupIds")) for (JsonNode id : p.path("secondaryMuscleGroupIds"))
                muscles.add(new Muscle(UUID.fromString(id.asText()), "secondary"));
            List<Equipment> equipment = new ArrayList<>();
            JsonNode eq = p.get("equipmentId");
            if (eq != null && !eq.isNull())
                equipment.add(new Equipment(UUID.fromString(eq.asText()), nullableInt(p.get("equipmentNumItems"))));
            List<String> instructions = null;
            if (p.has("instructions") && !p.get("instructions").isNull()) {
                instructions = new ArrayList<>();
                for (JsonNode x : p.get("instructions")) instructions.add(x.asText());
            }
            return new CustomExercise(UUID.fromString(p.path("id").asText()), p.path("name").asText(), nullableText(p.get("category")), nullableText(p.get("difficultyLevel")), nullableText(p.get("forceType")), nullableText(p.get("mechanics")), instructions, muscles, equipment);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid custom exercise");
        }
    }

    private String nullableText(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private Integer nullableInt(JsonNode n) {
        return n == null || n.isNull() ? null : n.intValue();
    }

    private Double nullableDouble(JsonNode n) {
        return n == null || n.isNull() ? null : n.doubleValue();
    }

    private Map<String, Object> wireWorkout(CanonicalAggregateRepository.WorkoutRead r) {
        return resourceMapper.workout(r);
    }

    private Map<String, Object> wireRoutine(CanonicalAggregateRepository.RoutineRead read) {
        return resourceMapper.routine(read);
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertEvidence(UUID owner, UUID id, byte[] raw, Map<String, Object> outcome) {
        jdbc.update("insert into processed_mutations(owner_id,mutation_id,request_kind,request_hash,request_bytes,outcome,created_at) values(?,?, 'sync', ?, ?, ?::jsonb, clock_timestamp())", owner, id, sha(raw), raw, json(outcome));
    }

    private byte[] sha(byte[] b) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> outcome(UUID id, String status, String error, Object batch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mutationId", id.toString());
        m.put("status", status);
        m.put("errorCode", error);
        m.put("batch", batch);
        return m;
    }

    private String sqlDomainCode(org.springframework.dao.DataIntegrityViolationException e) {
        if (e instanceof org.springframework.dao.DuplicateKeyException
                && "workout identity already reserved".equals(e.getMessage()))
            return "id_collision";

        java.sql.SQLException sql = null;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException exception) {
                sql = exception;
                break;
            }
        }
        if (sql == null) return null;

        String state = sql.getSQLState();
        // Only V3's explicit identity/reference guards are domain failures; other
        // integrity errors (including arbitrary CHECK violations) must abort the write.
        String diagnostic = sql.getMessage();
        String primary = diagnostic == null ? null : diagnostic.split("\\R", 2)[0];
        if ("23505".equals(state) && ("ERROR: child identity reservation conflict".equals(primary)
                || "ERROR: duplicate key value violates unique constraint \"root_id_reservations_pkey\"".equals(primary)
                || "ERROR: duplicate key value violates unique constraint \"child_id_reservations_pkey\"".equals(primary)))
            return "id_collision";
        if ("23503".equals(state) && "ERROR: exercise not visible to aggregate owner".equals(primary))
            return "invalid_reference";
        return null;
    }

    private record Previous(byte[] raw, String outcome) {
    }

    public static final class TemporarilyUnavailable extends RuntimeException {
    }
}
