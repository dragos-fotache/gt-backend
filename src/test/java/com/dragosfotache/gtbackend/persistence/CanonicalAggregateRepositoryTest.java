package com.dragosfotache.gtbackend.persistence;

import com.dragosfotache.gtbackend.catalogue.CatalogueImporter;
import com.dragosfotache.gtbackend.domain.AggregateValidator;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CanonicalAggregateRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    record Db(JdbcTemplate jdbc, TestRepo repo) {
    }

    static final class TestRepo {
        final CanonicalAggregateRepository delegate;
        final TransactionTemplate write, read;

        TestRepo(CanonicalAggregateRepository d, JdbcTemplate jdbc) {
            delegate = d;
            var tm = new DataSourceTransactionManager(jdbc.getDataSource());
            write = new TransactionTemplate(tm);
            read = new TransactionTemplate(tm);
            read.setReadOnly(true);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }

        void replaceActiveWorkoutRows(UUID o, Workout v) {
            write.executeWithoutResult(s -> delegate.replaceActiveWorkoutRows(o, v));
        }

        void importWorkoutRows(UUID o, Workout v) {
            write.executeWithoutResult(s -> delegate.importWorkoutRows(o, v));
        }

        void replaceRoutineRows(UUID o, Routine v, boolean create) {
            write.executeWithoutResult(s -> delegate.replaceRoutineRows(o, v, create));
        }

        void replaceCustomExerciseRows(UUID o, CustomExercise v) {
            write.executeWithoutResult(s -> delegate.replaceCustomExerciseRows(o, v));
        }

        CanonicalAggregateRepository.WorkoutRead readWorkout(UUID o, UUID id) {
            return read.execute(s -> delegate.readWorkout(o, id));
        }

        CanonicalAggregateRepository.RoutineRead readRoutine(UUID o, UUID id) {
            return read.execute(s -> delegate.readRoutine(o, id));
        }

        CustomExercise readCustomExercise(UUID o, UUID id) {
            return read.execute(s -> delegate.readCustomExercise(o, id));
        }
    }

    Db db() {
        String schema = "agg_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        new JdbcTemplate(admin).execute("create schema " + schema);
        var ds = new DriverManagerDataSource(pg.getJdbcUrl() + "&currentSchema=" + schema, pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        try {
            new CatalogueImporter(jdbc, new ObjectMapper()).install();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Db(jdbc, new TestRepo(new CanonicalAggregateRepository(jdbc, new AccountWriteRepository(jdbc)), jdbc));
    }

    UUID globalExercise(Db d) {
        return d.jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class);
    }

    UUID muscle(Db d) {
        return d.jdbc.queryForObject("select id from muscle_groups limit 1", UUID.class);
    }

    UUID equipment(Db d) {
        return d.jdbc.queryForObject("select id from equipment limit 1", UUID.class);
    }

    UUID account(Db d) {
        UUID id = UUID.randomUUID();
        d.jdbc.update("insert into accounts(id) values(?)", id);
        return id;
    }

    @Test
    void roundTripsCompleteRootsAndFreshNamesWithoutParentRevisionBump() {
        Db d = db();
        UUID owner = account(d);
        UUID muscle = muscle(d), equipment = equipment(d), custom = UUID.randomUUID();
        CustomExercise ce = new CustomExercise(custom, "Old name", null, "hard", null, "compound", null, List.of(new Muscle(muscle, "primary")), List.of(new Equipment(equipment, 0)));
        d.repo.replaceCustomExerciseRows(owner, ce);
        assertEquals(ce.id(), d.repo.readCustomExercise(owner, custom).id());
        assertEquals(ce.name(), d.repo.readCustomExercise(owner, custom).name());
        assertNull(d.repo.readCustomExercise(owner, custom).instructions());
        assertEquals(1, d.repo.readCustomExercise(owner, custom).revision());
        d.repo.replaceCustomExerciseRows(owner, new CustomExercise(custom, "Old name", null, "hard", null, "compound", List.of(), ce.muscles(), ce.equipment()));
        assertEquals(List.of(), d.repo.readCustomExercise(owner, custom).instructions());
        UUID workout = UUID.randomUUID(), we = UUID.randomUUID(), set = UUID.randomUUID();
        var sv = new SetValue(set, 0, 0, 0.0, false, 0);
        var ex = new WorkoutExercise(we, custom, 0, "notes", 0, List.of(sv));
        var w = new Workout(workout, "", WorkoutStatus.ACTIVE, Instant.parse("2024-01-01T00:00:00.000Z"), null, 0, List.of(ex));
        d.repo.importWorkoutRows(owner, w);
        var got = d.repo.readWorkout(owner, workout);
        assertEquals(new Workout(workout, "", WorkoutStatus.ACTIVE, Instant.parse("2024-01-01T00:00:00.000Z"), null, 1, List.of(ex)), got.root());
        assertEquals(ex, got.exercises().getFirst().value());
        assertEquals("Old name", got.exercises().getFirst().exerciseName());
        UUID routine = UUID.randomUUID(), re = UUID.randomUUID(), rs = UUID.randomUUID();
        Routine r = new Routine(routine, "", true, 0, List.of(new RoutineExercise(re, custom, 0, 30, List.of(new RoutineSet(rs, 0, null, 0.0)))));
        d.repo.replaceRoutineRows(owner, r, true);
        assertEquals(new Routine(routine, "", true, 1, r.exercises()), d.repo.readRoutine(owner, routine).root());
        d.repo.replaceCustomExerciseRows(owner, new CustomExercise(custom, "Renamed", null, "hard", null, "compound", null, List.of(new Muscle(muscle, "primary")), List.of(new Equipment(equipment, 0))));
        assertEquals(3, d.repo.readCustomExercise(owner, custom).revision());
        assertEquals("Renamed", d.repo.readWorkout(owner, workout).exercises().getFirst().exerciseName());
        assertEquals("Renamed", d.repo.readRoutine(owner, routine).exercises().getFirst().exerciseName());
        assertEquals(1, d.jdbc.queryForObject("select revision from workouts where id=?", Integer.class, workout));
        assertEquals(1, d.jdbc.queryForObject("select revision from routines where id=?", Integer.class, routine));
    }

    @Test
    void ownerScopedNestedReferencesAndInvalidReplacementRollbackAllEffects() {
        Db d = db();
        UUID a = account(d), b = account(d), foreign = UUID.randomUUID(), global = globalExercise(d);
        d.jdbc.update("insert into exercises(id,owner_id,is_user_created,name,type,created_at,updated_at,revision) values(?,?,true,'private','reps',now(),now(),1)", foreign, b);
        UUID root = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,now(),'active',1)", root, a, "x");
        UUID entry = UUID.randomUUID(), set = UUID.randomUUID();
        Instant time = Instant.parse("2024-01-01T00:00:00.000Z");
        var original = new Workout(root, "x", WorkoutStatus.ACTIVE, time, null, 0, List.of(new WorkoutExercise(entry, global, 0, "original", 30, List.of(new SetValue(set, 0, null, null, false, null)))));
        d.repo.replaceActiveWorkoutRows(a, original);
        Integer reservations = d.jdbc.queryForObject("select count(*) from child_id_reservations where owner_id=?", Integer.class, a);
        UUID invalidEntry = UUID.randomUUID();
        var invalid = new Workout(root, "mutated", WorkoutStatus.ACTIVE, time, null, 0, List.of(new WorkoutExercise(invalidEntry, foreign, 0, "", 30, List.of())));
        assertThrows(RuntimeException.class, () -> d.repo.replaceActiveWorkoutRows(a, invalid));
        assertEquals("x", d.jdbc.queryForObject("select name from workouts where id=?", String.class, root));
        assertEquals(2, d.jdbc.queryForObject("select revision from workouts where id=?", Integer.class, root));
        assertEquals(1, d.jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, root));
        assertEquals(1, d.jdbc.queryForObject("select count(*) from workout_sets where id=?", Integer.class, set));
        assertEquals(reservations, d.jdbc.queryForObject("select count(*) from child_id_reservations where owner_id=?", Integer.class, a));
        assertNull(d.repo.readWorkout(a, UUID.randomUUID()));
        assertNull(d.repo.readWorkout(b, root));
    }

    @Test
    void completeReplacementRetainsIdsAndAllowsRestoreToSameParent() {
        Db d = db();
        UUID owner = account(d), global = globalExercise(d), root = UUID.randomUUID(), entry = UUID.randomUUID(), set = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,now(),'active',1)", root, owner, "initial");
        var time = Instant.parse("2024-01-01T00:00:00.000Z");
        var one = new Workout(root, "changed", WorkoutStatus.ACTIVE, time, null, 0, List.of(new WorkoutExercise(entry, global, 0, "n", 30, List.of(new SetValue(set, 0, null, null, false, null)))));
        d.repo.replaceActiveWorkoutRows(owner, one);
        assertEquals(entry, d.repo.readWorkout(owner, root).exercises().getFirst().value().id());
        var empty = new Workout(root, "changed", WorkoutStatus.ACTIVE, time, null, 0, List.of());
        d.repo.replaceActiveWorkoutRows(owner, empty);
        d.repo.replaceActiveWorkoutRows(owner, one);
        assertEquals(set, d.repo.readWorkout(owner, root).exercises().getFirst().value().sets().getFirst().id());
        assertEquals(4, d.jdbc.queryForObject("select revision from workouts where id=?", Integer.class, root));
    }

    @Test
    void savedRoutineAutosaveAndFinishedImportHaveDistinctRules() {
        Db d = db();
        UUID owner = account(d), global = globalExercise(d), routine = UUID.randomUUID();
        d.jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,false,1)", routine, owner, "Previously saved");
        d.repo.replaceRoutineRows(owner, new Routine(routine, "", false, 0, List.of()), false);
        assertFalse(d.repo.readRoutine(owner, routine).root().draft());
        assertEquals("", d.repo.readRoutine(owner, routine).root().name());
        UUID wid = UUID.randomUUID(), entry = UUID.randomUUID(), sid = UUID.randomUUID();
        Instant start = Instant.parse("2024-01-01T00:00:00.000Z"), finish = start.minusMillis(1);
        var valid = new Workout(wid, "history", WorkoutStatus.FINISHED, start, finish, 0, List.of(new WorkoutExercise(entry, global, 0, "n", 30, List.of(new SetValue(sid, 0, 0, 0.0, true, 0)))));
        d.repo.importWorkoutRows(owner, valid);
        assertEquals(WorkoutStatus.FINISHED, d.repo.readWorkout(owner, wid).root().status());
        UUID rejected = UUID.randomUUID();
        var invalid = new Workout(rejected, "bad", WorkoutStatus.FINISHED, start, finish, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> d.repo.importWorkoutRows(owner, invalid));
        assertEquals(0, d.jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, rejected));
        assertThrows(IllegalArgumentException.class, () -> d.repo.replaceActiveWorkoutRows(owner, valid));
    }

    @Test
    void validatorRejectsFamilyIdentityReuseAndBadShape() {
        UUID root = UUID.randomUUID(), entry = UUID.randomUUID();
        Instant t = Instant.parse("2024-01-01T00:00:00.000Z");
        var repeated = new Workout(root, "x", WorkoutStatus.ACTIVE, t, null, 0, List.of(new WorkoutExercise(entry, UUID.randomUUID(), 0, "", 30, List.of(new SetValue(entry, 0, null, null, false, null)))));
        assertThrows(IllegalArgumentException.class, () -> AggregateValidator.workout(repeated, false));
        var precise = new Workout(UUID.randomUUID(), "x", WorkoutStatus.ACTIVE, t.plusNanos(1), null, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> AggregateValidator.workout(precise, false));
        assertThrows(IllegalArgumentException.class, () -> AggregateValidator.customExercise(new CustomExercise(UUID.randomUUID(), "x", null, null, null, null, List.of(), List.of(new Muscle(entry, "primary"), new Muscle(entry, "secondary")), List.of())));
        assertThrows(IllegalArgumentException.class, () -> AggregateValidator.customExercise(new CustomExercise(UUID.randomUUID(), "x", null, null, null, null, List.of(), List.of(), List.of(new Equipment(UUID.randomUUID(), -1)))));
        var nan = new Workout(UUID.randomUUID(), "x", WorkoutStatus.ACTIVE, t, null, 0, List.of(new WorkoutExercise(UUID.randomUUID(), UUID.randomUUID(), 0, "", 30, List.of(new SetValue(UUID.randomUUID(), 0, null, Double.NaN, false, null)))));
        assertThrows(IllegalArgumentException.class, () -> AggregateValidator.workout(nan, false));
    }

    @Test
    void lockedRoutinePrimitiveRequiresActiveTransaction() {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID();
        Routine draft = new Routine(root, "", true, 0, List.of());
        assertThrows(IllegalStateException.class, () -> d.repo.delegate.replaceRoutineRowsInLockedAccount(owner, draft, true));
        assertEquals(0, d.jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, root));
    }

    @Test
    void operationScopesDoNotCreateOrChangeRoutineState() {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID(), exercise = globalExercise(d);
        var w = new Workout(root, "x", WorkoutStatus.ACTIVE, Instant.parse("2024-01-01T00:00:00Z"), null, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> d.repo.replaceActiveWorkoutRows(owner, w));
        UUID rid = UUID.randomUUID();
        Routine saved = new Routine(rid, "saved", false, 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> d.repo.replaceRoutineRows(owner, saved, true));
        Routine draft = new Routine(rid, "", true, 0, List.of());
        d.repo.replaceRoutineRows(owner, draft, true);
        assertThrows(IllegalArgumentException.class, () -> d.repo.replaceRoutineRows(owner, new Routine(rid, "saved", false, 0, List.of()), false));
    }
}
