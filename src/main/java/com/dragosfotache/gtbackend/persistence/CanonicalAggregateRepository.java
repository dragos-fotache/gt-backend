package com.dragosfotache.gtbackend.persistence;

import com.dragosfotache.gtbackend.domain.AggregateValidator;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Deliberately low-level aggregate row adapter, NOT an accepted mutation API: every method must be
 * called inside the future mutation engine's AccountWriteRepository transaction, which must also
 * allocate/publish its change batch. It never chooses a server revision from input.
 */
@Repository
public class CanonicalAggregateRepository {
    private final JdbcTemplate jdbc;
    private final AccountWriteRepository accounts;
    private final ObjectMapper json = new ObjectMapper();

    public CanonicalAggregateRepository(JdbcTemplate jdbc, AccountWriteRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /**
     * Low-level active-only replacement of an existing root; root creation/import is separate.
     */
    public void replaceActiveWorkoutRows(UUID owner, Workout value) {
        AggregateValidator.workout(value, false);
        accounts.inAccountWrite(owner, session -> {
            replaceActiveWorkoutRowsInLockedAccount(owner, value);
            return null;
        });
    }

    public void replaceActiveWorkoutRowsInLockedAccount(UUID owner, Workout value) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("workout replacement requires an active account transaction");
        AggregateValidator.workout(value, false);
        String status = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, value.id(), owner);
        if (status == null) throw new IllegalArgumentException("workout not found");
        if (!status.equals("active")) throw new IllegalStateException("finished workout is immutable");
        jdbc.update("update workouts set name=?,started_at=?,revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=?", value.name(), Timestamp.from(value.startedAt()), value.id(), owner);
        writeWorkoutChildren(owner, value);
    }

    public long finishWorkoutInLockedAccount(UUID owner, UUID id, Instant finishedAt) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("workout finish requires an active account transaction");
        String status = jdbc.query("select status from workouts where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, id, owner);
        if (status == null) throw new IllegalArgumentException("workout not found");
        if (!status.equals("active")) throw new IllegalStateException("workout is not active");
        Integer complete = jdbc.queryForObject("select count(*) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id where e.workout_id=? and s.is_completed", Integer.class, id);
        if (complete == 0) throw new NoCompletedSetsException();
        jdbc.update("delete from workout_sets s using workout_exercises e where s.workout_exercise_id=e.id and e.workout_id=? and not s.is_completed", id);
        jdbc.update("delete from workout_exercises e where e.workout_id=? and not exists(select 1 from workout_sets s where s.workout_exercise_id=e.id)", id);
        jdbc.update("update workout_exercises set position=position+(select coalesce(max(position),0)+1 from workout_exercises where workout_id=?) where workout_id=?", id, id);
        jdbc.update("with ranked as (select id,row_number() over(order by position)-1 as p from workout_exercises where workout_id=?) update workout_exercises e set position=ranked.p from ranked where e.id=ranked.id", id);
        jdbc.update("update workout_sets s set position=s.position+(select coalesce(max(x.position),0)+1 from workout_sets x where x.workout_exercise_id=s.workout_exercise_id) from workout_exercises e where s.workout_exercise_id=e.id and e.workout_id=?", id);
        jdbc.update("with ranked as (select s.id,row_number() over(partition by s.workout_exercise_id order by s.position)-1 as p from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id where e.workout_id=?) update workout_sets s set position=ranked.p from ranked where s.id=ranked.id", id);
        Long revision = jdbc.query("update workouts set status='finished',finished_at=?,revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=? returning revision", rs -> rs.next() ? rs.getLong(1) : null, Timestamp.from(finishedAt), id, owner);
        return revision;
    }

    public static final class NoCompletedSetsException extends RuntimeException {
    }

    /**
     * Create-only import primitive. Caller must enforce import policy and publish in same transaction.
     */
    public void importWorkoutRows(UUID owner, Workout value) {
        AggregateValidator.workout(value, true);
        accounts.inAccountWrite(owner, session -> {
            jdbc.update("insert into workouts(id,owner_id,name,started_at,finished_at,status,revision) values(?,?,?,?,?,?,1)", value.id(), owner, value.name(), Timestamp.from(value.startedAt()), value.finishedAt() == null ? null : Timestamp.from(value.finishedAt()), value.status().name().toLowerCase());
            writeWorkoutChildren(owner, value);
            return null;
        });
    }

    public void importWorkoutRowsInLockedAccount(UUID owner, Workout value) {
        AggregateValidator.workout(value, true);
        jdbc.update("insert into workouts(id,owner_id,name,started_at,finished_at,status,revision) values(?,?,?,?,?,?,1)", value.id(), owner, value.name(), Timestamp.from(value.startedAt()), value.finishedAt() == null ? null : Timestamp.from(value.finishedAt()), value.status().name().toLowerCase());
        writeWorkoutChildren(owner, value);
    }

    public StartWorkoutResult importActiveWorkoutInLockedAccount(UUID owner, Workout value) {
        if (value.status() != WorkoutStatus.ACTIVE) throw new IllegalArgumentException("not active");
        return createActiveWorkoutInLockedAccount(owner, value);
    }

    public void importRoutineRowsInLockedAccount(UUID owner, Routine value) {
        AggregateValidator.routine(value, value.draft());
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,?,1)", value.id(), owner, value.name(), value.draft());
        writeRoutineChildren(owner, value);
    }

    public void importCustomExerciseRowsInLockedAccount(UUID owner, CustomExercise value) {
        replaceCustomExerciseRowsInLockedAccount(owner, value);
    }

    /**
     * Starts a fresh active workout and tombstones the prior active root under the caller's account lock.
     */
    public StartWorkoutResult startWorkoutRowsInLockedAccount(UUID owner, Workout value) {
        if (!value.exercises().isEmpty()) throw new IllegalArgumentException("workout.start must be empty");
        return createActiveWorkoutInLockedAccount(owner, value);
    }

    /**
     * Clone command variant: children are already validated and carry fresh client-bound IDs.
     */
    public StartWorkoutResult startWorkoutCloneRowsInLockedAccount(UUID owner, Workout value) {
        return createActiveWorkoutInLockedAccount(owner, value);
    }

    private StartWorkoutResult createActiveWorkoutInLockedAccount(UUID owner, Workout value) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("workout start requires an active account transaction");
        AggregateValidator.workout(value, false);
        Integer collision = jdbc.query("select 1 from root_id_reservations where aggregate_type='workout' and aggregate_id=?", rs -> rs.next() ? 1 : null, value.id());
        if (collision != null)
            throw new org.springframework.dao.DuplicateKeyException("workout identity already reserved");
        record OldActive(UUID id, long revision) {
        }
        OldActive old = jdbc.query("select id,revision from workouts where owner_id=? and status='active' and deleted_at is null for update", rs -> rs.next() ? new OldActive(rs.getObject(1, UUID.class), rs.getLong(2)) : null, owner);
        if (old != null)
            jdbc.update("update workouts set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=?", old.id(), owner);
        jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,?,'active',1)", value.id(), owner, value.name(), Timestamp.from(value.startedAt()));
        writeWorkoutChildren(owner, value);
        return new StartWorkoutResult(old == null ? null : old.id(), old == null ? null : old.revision() + 1);
    }

    public record StartWorkoutResult(UUID replacedId, Long replacedRevision) {
    }

    private void writeWorkoutChildren(UUID owner, Workout v) {
        jdbc.update("delete from workout_sets where workout_exercise_id in(select id from workout_exercises where workout_id=?)", v.id());
        jdbc.update("delete from workout_exercises where workout_id=?", v.id());
        for (var e : v.exercises()) {
            visible(owner, e.exerciseId());
            jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,notes,rest_duration_seconds) values(?,?,?,?,?,?)", e.id(), v.id(), e.exerciseId(), e.position(), e.notes(), e.restDurationSeconds());
            for (var s : e.sets())
                jdbc.update("insert into workout_sets(id,workout_exercise_id,position,repetitions,weight_kg,rest_seconds,is_completed) values(?,?,?,?,?,?,?)", s.id(), e.id(), s.position(), s.repetitions(), s.weightKg(), s.restSeconds(), s.completed());
        }
    }

    /**
     * Routine autosave is complete replacement; existing draft/saved state cannot transition here.
     */
    public void replaceRoutineRows(UUID owner, Routine v, boolean createDraft) {
        AggregateValidator.routine(v, createDraft);
        accounts.inAccountWrite(owner, session -> {
            replaceRoutineRowsLocked(owner, v, createDraft);
            return null;
        });
    }

    /**
     * Mutation-engine primitive. Caller must already hold the account row lock in its write transaction.
     */
    public void replaceRoutineRowsInLockedAccount(UUID owner, Routine v, boolean createDraft) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("routine write requires an active account transaction");
        Integer locked = jdbc.query("select 1 from accounts where id=? and deleted_at is null for update", rs -> rs.next() ? 1 : null, owner);
        if (locked == null) throw new IllegalArgumentException("unknown or disabled account");
        AggregateValidator.routine(v, createDraft);
        replaceRoutineRowsLocked(owner, v, createDraft);
    }

    private void replaceRoutineRowsLocked(UUID owner, Routine v, boolean createDraft) {
        String state = jdbc.query("select is_draft::text from routines where id=? and owner_id=? and deleted_at is null for update", rs -> rs.next() ? rs.getString(1) : null, v.id(), owner);
        if (state == null) {
            if (!createDraft || !v.draft()) throw new IllegalArgumentException("routine creation is draft-only");
            jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,?,1)", v.id(), owner, v.name(), true);
        } else if (Boolean.parseBoolean(state) != v.draft())
            throw new IllegalArgumentException("routine autosave cannot promote or demote");
        else
            jdbc.update("update routines set name=?,revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=?", v.name(), v.id(), owner);
        writeRoutineChildren(owner, v);
    }

    /**
     * Create-only saved-routine primitive for a validated semantic copy command.
     */
    public void createSavedRoutineCloneInLockedAccount(UUID owner, Routine value) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("routine clone requires an active account transaction");
        Integer locked = jdbc.query("select 1 from accounts where id=? and deleted_at is null for update", rs -> rs.next() ? 1 : null, owner);
        if (locked == null) throw new IllegalArgumentException("unknown or disabled account");
        AggregateValidator.routine(value, false);
        AggregateValidator.savedRoutineEligible(value);
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,false,1)", value.id(), owner, value.name());
        writeRoutineChildren(owner, value);
    }

    private void writeRoutineChildren(UUID owner, Routine v) {
        jdbc.update("delete from routine_sets where routine_exercise_id in(select id from routine_exercises where routine_id=?)", v.id());
        jdbc.update("delete from routine_exercises where routine_id=?", v.id());
        for (var e : v.exercises()) {
            visible(owner, e.exerciseId());
            jdbc.update("insert into routine_exercises(id,routine_id,exercise_id,position,rest_duration_seconds) values(?,?,?,?,?)", e.id(), v.id(), e.exerciseId(), e.position(), e.restDurationSeconds());
            for (var s : e.sets())
                jdbc.update("insert into routine_sets(id,routine_exercise_id,position,repetitions,weight_kg) values(?,?,?,?,?)", s.id(), e.id(), s.position(), s.repetitions(), s.weightKg());
        }
    }

    /**
     * Complete custom-exercise relationship replacement; null instructions differs from [].
     */
    public void replaceCustomExerciseRows(UUID owner, CustomExercise e) {
        AggregateValidator.customExercise(e);
        accounts.inAccountWrite(owner, session -> {
            replaceCustomExerciseRowsInLockedAccount(owner, e);
            return null;
        });
    }

    /**
     * Mutation-engine primitive; caller owns the account transaction and rollback boundary.
     */
    public void replaceCustomExerciseRowsInLockedAccount(UUID owner, CustomExercise e) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("custom exercise write requires an active account transaction");
        AggregateValidator.customExercise(e);
        String existing = jdbc.query("select 1 from exercises where id=? and owner_id=? and is_user_created=true and deleted_at is null for update", rs -> rs.next() ? "yes" : null, e.id(), owner);
        if (existing == null)
            jdbc.update("insert into exercises(id,owner_id,is_user_created,name,type,category,difficulty_level,force_type,mechanics,instructions,created_at,updated_at,revision) values(?,?,true,?,'reps',?,?,?, ?,?::jsonb,clock_timestamp(),clock_timestamp(),1)", e.id(), owner, e.name(), e.category(), e.difficultyLevel(), e.forceType(), e.mechanics(), jsonValue(e.instructions()));
        else {
            jdbc.update("update exercises set name=?,category=?,difficulty_level=?,force_type=?,mechanics=?,instructions=?::jsonb,revision=revision+1,updated_at=clock_timestamp() where id=? and owner_id=?", e.name(), e.category(), e.difficultyLevel(), e.forceType(), e.mechanics(), jsonValue(e.instructions()), e.id(), owner);
            jdbc.update("delete from exercise_muscle_groups where exercise_id=?", e.id());
            jdbc.update("delete from exercise_equipment where exercise_id=?", e.id());
        }
        for (var m : e.muscles()) {
            if (!exists("select 1 from muscle_groups where id=?", m.id()))
                throw new InvalidReferenceException();
            jdbc.update("insert into exercise_muscle_groups(exercise_id,muscle_group_id,type) values(?,?,?)", e.id(), m.id(), m.role());
        }
        for (var x : e.equipment()) {
            if (!exists("select 1 from equipment where id=?", x.id()))
                throw new InvalidReferenceException();
            jdbc.update("insert into exercise_equipment(exercise_id,equipment_id,num_items) values(?,?,?)", e.id(), x.id(), x.quantity());
        }
    }

    /**
     * Current canonical state with labels joined at read time; labels never modify root revisions.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkoutRead readWorkout(UUID owner, UUID id) {
        var root = jdbc.query("select id,name,status,started_at,finished_at,revision from workouts where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? new Workout(rs.getObject(1, UUID.class), rs.getString(2), WorkoutStatus.valueOf(rs.getString(3).toUpperCase(Locale.ROOT)), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getLong(6), new ArrayList<>()) : null, id, owner);
        if (root == null) return null;
        List<WorkoutExerciseRead> ex = jdbc.query("select we.id,we.exercise_id,we.position,we.notes,we.rest_duration_seconds,x.name from workout_exercises we join exercises x on x.id=we.exercise_id where we.workout_id=? and (x.owner_id is null or (x.owner_id=? and x.deleted_at is null)) order by we.position", (rs, n) -> {
            UUID eid = rs.getObject(1, UUID.class);
            List<SetValue> sets = jdbc.query("select id,position,repetitions,weight_kg,is_completed,rest_seconds from workout_sets where workout_exercise_id=? order by position", (s, k) -> new SetValue(s.getObject(1, UUID.class), s.getInt(2), (Integer) s.getObject(3), (Double) s.getObject(4), s.getBoolean(5), (Integer) s.getObject(6)), eid);
            return new WorkoutExerciseRead(new WorkoutExercise(eid, rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getInt(5), sets), rs.getString(6));
        }, id, owner);
        Workout complete = new Workout(root.id(), root.name(), root.status(), root.startedAt(), root.finishedAt(), root.revision(), ex.stream().map(WorkoutExerciseRead::value).toList());
        return new WorkoutRead(complete, ex);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoutineRead readRoutine(UUID owner, UUID id) {
        var root = jdbc.query("select id,name,is_draft,revision from routines where id=? and owner_id=? and deleted_at is null", rs -> rs.next() ? new Routine(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getLong(4), new ArrayList<>()) : null, id, owner);
        if (root == null) return null;
        List<RoutineExerciseRead> ex = jdbc.query("select re.id,re.exercise_id,re.position,re.rest_duration_seconds,x.name from routine_exercises re join exercises x on x.id=re.exercise_id where re.routine_id=? and (x.owner_id is null or (x.owner_id=? and x.deleted_at is null)) order by re.position", (rs, n) -> {
            UUID eid = rs.getObject(1, UUID.class);
            List<RoutineSet> sets = jdbc.query("select id,position,repetitions,weight_kg from routine_sets where routine_exercise_id=? order by position", (s, k) -> new RoutineSet(s.getObject(1, UUID.class), s.getInt(2), (Integer) s.getObject(3), (Double) s.getObject(4)), eid);
            return new RoutineExerciseRead(new RoutineExercise(eid, rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), sets), rs.getString(5));
        }, id, owner);
        Routine complete = new Routine(root.id(), root.name(), root.draft(), root.revision(), ex.stream().map(RoutineExerciseRead::value).toList());
        return new RoutineRead(complete, ex);
    }

    /** Load bundled or caller-owned live exercise data and all named relationships in the caller's read snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExerciseValue readVisibleExercise(UUID owner, UUID id) {
        ExerciseRow row = jdbc.query("select id,name,type,category,difficulty_level,force_type,mechanics,instructions,is_user_created,revision "
                        + "from exercises where id=? and deleted_at is null and (owner_id is null or owner_id=?)",
                rs -> rs.next() ? new ExerciseRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getBoolean(9), (Long) rs.getObject(10)) : null, id, owner);
        if (row == null) return null;
        List<String> instructions = row.instructions() == null ? null : parseInstructions(row.instructions());
        List<ExerciseMuscle> muscles = jdbc.query("select mg.id,mg.name,emg.type from exercise_muscle_groups emg "
                        + "join muscle_groups mg on mg.id=emg.muscle_group_id where emg.exercise_id=? "
                        + "order by case emg.type when 'primary' then 0 when 'secondary' then 1 when 'tertiary' then 2 else 3 end,mg.id",
                (rs, n) -> new ExerciseMuscle(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), id);
        List<ExerciseEquipment> equipment = jdbc.query("select e.id,e.name,ee.num_items from exercise_equipment ee "
                        + "join equipment e on e.id=ee.equipment_id where ee.exercise_id=? order by e.id",
                (rs, n) -> new ExerciseEquipment(rs.getObject(1, UUID.class), rs.getString(2), (Integer) rs.getObject(3)), id);
        return new ExerciseValue(row.id(), row.name(), row.type(), row.category(), row.difficultyLevel(),
                row.forceType(), row.mechanics(), instructions, row.userCreated(), row.userCreated() ? row.revision() : null,
                muscles, equipment);
    }

    private record ExerciseRow(UUID id, String name, String type, String category, String difficultyLevel,
                               String forceType, String mechanics, String instructions, boolean userCreated,
                               Long revision) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CustomExercise readCustomExercise(UUID owner, UUID id) {
        return jdbc.query("select id,name,category,difficulty_level,force_type,mechanics,instructions,revision from exercises where id=? and owner_id=? and is_user_created=true and deleted_at is null", rs -> {
            if (!rs.next()) return null;
            List<String> instructions = null;
            String raw = rs.getString(7);
            if (raw != null) instructions = parseInstructions(raw);
            List<Muscle> muscles = jdbc.query("select muscle_group_id,type from exercise_muscle_groups where exercise_id=? order by case type when 'primary' then 0 when 'secondary' then 1 else 2 end,muscle_group_id", (r, n) -> new Muscle(r.getObject(1, UUID.class), r.getString(2)), id);
            List<Equipment> equipment = jdbc.query("select equipment_id,num_items from exercise_equipment where exercise_id=? order by equipment_id", (r, n) -> new Equipment(r.getObject(1, UUID.class), (Integer) r.getObject(2)), id);
            return new CustomExercise(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), instructions, muscles, equipment, rs.getLong(8));
        }, id, owner);
    }

    public record WorkoutExerciseRead(WorkoutExercise value, String exerciseName) {
    }

    public record WorkoutRead(Workout root, List<WorkoutExerciseRead> exercises) {
    }

    public record RoutineExerciseRead(RoutineExercise value, String exerciseName) {
    }

    public record RoutineRead(Routine root, List<RoutineExerciseRead> exercises) {
    }

    private String jsonValue(Object value) {
        try {
            return value == null ? null : json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid instructions", e);
        }
    }

    private List<String> parseInstructions(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("corrupt stored instructions", e);
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.query(sql, rs -> rs.next() ? 1 : 0, args);
        return n != null && n == 1;
    }

    private void visible(UUID owner, UUID id) {
        if (!exists("select 1 from exercises where id=? and (owner_id is null or owner_id=?) and deleted_at is null", id, owner))
            throw new InvalidReferenceException();
    }

    public static final class InvalidReferenceException extends IllegalArgumentException {
        public InvalidReferenceException() {
            super("invalid reference");
        }
    }
}
