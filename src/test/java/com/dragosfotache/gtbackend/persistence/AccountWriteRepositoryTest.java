package com.dragosfotache.gtbackend.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AccountWriteRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    record Db(JdbcTemplate jdbc, AccountWriteRepository repository, TransactionTemplate tx) {
    }

    Db db() {
        String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new JdbcTemplate(admin).execute("create schema " + schema);
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl() + "&currentSchema=" + schema, postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        return new Db(jdbc, new AccountWriteRepository(jdbc), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    UUID account(Db d) {
        UUID id = UUID.randomUUID();
        d.jdbc.update("insert into accounts(id) values (?)", id);
        return id;
    }

    long write(Db d, UUID owner, java.util.function.Function<AccountWriteRepository.WriteSession, Long> work) {
        return d.tx.execute(status -> d.repository.inAccountWrite(owner, work));
    }

    AccountWriteRepository.Change change(UUID id, long revision) {
        return new AccountWriteRepository.Change("workout", id, "upsert", revision, "{}");
    }

    @Test
    void ownershipVisibilityAndReferencesAreAccountScoped() {
        Db d = db();
        UUID a = account(d), b = account(d), workout = UUID.randomUUID(), foreignCustom = UUID.randomUUID();
        d.jdbc.update("insert into exercises(id,owner_id,is_user_created,name,type,created_at,updated_at,revision) values (?,?,true,'private','reps',now(),now(),1)", foreignCustom, b);
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", workout, a, "private");
        assertTrue(d.repository.findWorkout(a, workout).isPresent());
        assertTrue(d.repository.findWorkout(b, workout).isEmpty());
        assertThrows(RuntimeException.class, () -> d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", UUID.randomUUID(), workout, foreignCustom));
        assertEquals(0, count(d, "workout_exercises"));
    }

    @Test
    void reservationsRemainBoundAfterRemovalAndRootTombstone() {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID(), entry = UUID.randomUUID(), set = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", root, owner, "x");
        UUID exercise = UUID.randomUUID();
        d.jdbc.update("insert into exercises(id,name,type,is_user_created) values (?,?,?,false)", exercise, "global", "reps");
        d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", entry, root, exercise);
        d.jdbc.update("insert into workout_sets(id,workout_exercise_id,position,is_completed) values (?,?,0,false)", set, entry);
        d.jdbc.update("delete from workout_sets where id=?", set);
        d.jdbc.update("delete from workout_exercises where id=?", entry);
        assertEquals(3, d.jdbc.queryForObject("select count(*) from child_id_reservations where family='workout'", Integer.class));
        // Exact child identities may return to their original parent after full autosave.
        d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", entry, root, exercise);
        d.jdbc.update("insert into workout_sets(id,workout_exercise_id,position,is_completed) values (?,?,0,false)", set, entry);
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_exercises set id=? where id=?", UUID.randomUUID(), entry));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_sets set id=? where id=?", UUID.randomUUID(), set));
        d.jdbc.update("update workouts set status='finished',finished_at=now() where id=?", root);
        UUID otherRoot = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,finished_at,revision) values (?,?,?,now(),'finished',now(),1)", otherRoot, owner, "other");
        UUID otherEntry = UUID.randomUUID();
        d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", otherEntry, otherRoot, exercise);
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_exercises set workout_id=? where id=?", otherRoot, entry));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_sets set workout_exercise_id=? where id=?", otherEntry, set));
        d.jdbc.update("update workouts set deleted_at=now(),revision=revision+1 where id=?", root);
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_exercises set notes='blocked' where id=?", entry));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workout_sets set is_completed=true where id=?", set));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("delete from workout_exercises where id=?", entry));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("delete from workout_sets where id=?", set));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workouts set name='blocked' where id=?", root));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", UUID.randomUUID(), root, exercise));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", root, owner, "resurrect"));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", entry, otherRoot, exercise));
    }

    @Test
    void disabledAccountPurgeMayDeleteTombstoneChildrenButReservationsRemainUntilPurge() {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID(), entry = UUID.randomUUID(), set = UUID.randomUUID();
        UUID exercise = UUID.randomUUID();
        d.jdbc.update("insert into exercises(id,name,type,is_user_created) values (?,?,?,false)", exercise, "global", "reps");
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", root, owner, "purge");
        d.jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,rest_duration_seconds) values (?,?,?,0,30)", entry, root, exercise);
        d.jdbc.update("insert into workout_sets(id,workout_exercise_id,position,is_completed) values (?,?,0,false)", set, entry);
        d.jdbc.update("update workouts set deleted_at=now(),revision=revision+1 where id=?", root);
        assertThrows(RuntimeException.class, () -> d.jdbc.update("delete from workout_sets where id=?", set));
        d.jdbc.update("update accounts set deleted_at=now() where id=?", owner);
        d.jdbc.update("delete from workout_sets where id=?", set);
        d.jdbc.update("delete from workout_exercises where id=?", entry);
        d.jdbc.update("delete from workouts where id=?", root);
        assertEquals(0, count(d, "workouts"));
        assertEquals(0, count(d, "workout_exercises"));
        assertEquals(0, count(d, "workout_sets"));
        assertEquals(3, d.jdbc.queryForObject("select count(*) from child_id_reservations where family='workout'", Integer.class));
        assertEquals(1, count(d, "root_id_reservations"));
        // The later account-purge phase, unlike ordinary root pruning, removes reservations.
        d.jdbc.update("delete from child_id_reservations where owner_id=?", owner);
        d.jdbc.update("delete from root_id_reservations where owner_id=?", owner);
        d.jdbc.update("delete from account_sync_state where account_id=?", owner);
        d.jdbc.update("delete from accounts where id=?", owner);
        assertEquals(0, count(d, "child_id_reservations"));
    }

    @Test
    void databaseEnforcesSingleLiveActiveWorkout() {
        Db d = db();
        UUID owner = account(d);
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", UUID.randomUUID(), owner, "one");
        assertThrows(RuntimeException.class, () -> d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", UUID.randomUUID(), owner, "two"));
        // Tombstones release the active slot; finished roots do not consume it.
        d.jdbc.update("update workouts set deleted_at=now() where owner_id=?", owner);
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", UUID.randomUUID(), owner, "next");
    }

    @Test
    void revisionAllocatorRejectsTombstones() {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',3)", root, owner, "deleted");
        d.jdbc.update("update workouts set deleted_at=now() where id=?", root);
        assertThrows(RuntimeException.class, () -> write(d, owner, session -> session.nextRevision("workout", root)));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workouts set name='resurrected' where id=?", root));
        assertThrows(RuntimeException.class, () -> d.jdbc.update("update workouts set deleted_at=null where id=?", root));
        d.jdbc.update("update workouts set revision=revision+1,updated_at=clock_timestamp() where id=?", root);
        assertEquals(4, d.jdbc.queryForObject("select revision from workouts where id=?", Integer.class, root));
    }

    @Test
    void writeRollbackRemovesDomainRevisionBatchAndCursorTogether() {
        Db d = db();
        UUID owner = account(d), workout = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> d.tx.executeWithoutResult(s -> d.repository.inAccountWrite(owner, session -> {
            d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", workout, owner, "rollback");
            session.publish(List.of(change(workout, 1)));
            throw new IllegalStateException("abort");
        })));
        assertEquals(0, count(d, "workouts"));
        assertEquals(0, count(d, "sync_change_batches"));
        assertEquals(0, d.jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Integer.class, owner));
    }

    @Test
    void accountLockSerializesSameOwnerAndReadersNeverSeeUncommittedCursor() throws Exception {
        Db d = db();
        UUID owner = account(d), root = UUID.randomUUID();
        d.jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values (?,?,?,now(),'active',1)", root, owner, "serialized");
        var allocated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> d.tx.execute(status -> d.repository.inAccountWrite(owner, session -> {
                long revision = session.nextRevision("workout", root);
                session.publish(List.of(change(root, revision)));
                allocated.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return null;
            })));
            assertTrue(allocated.await(10, TimeUnit.SECONDS));
            assertEquals(0, d.jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Integer.class, owner));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return d.tx.execute(status -> d.repository.inAccountWrite(owner, s -> {
                    long revision = s.nextRevision("workout", root);
                    return s.publish(List.of(change(root, revision)));
                }));
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertFalse(second.isDone(), "same-account writer passed the held row lock");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(2, second.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(2, d.jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Integer.class, owner));
        assertEquals(2, count(d, "sync_change_batches"));
        assertEquals(3, d.jdbc.queryForObject("select revision from workouts where id=?", Integer.class, root));
        assertTrue(d.jdbc.queryForObject("select a.committed_at < b.committed_at from sync_change_batches a join sync_change_batches b on b.account_id=a.account_id and b.cursor=a.cursor+1 where a.account_id=?", Boolean.class, owner));
    }

    private static long count(Db d, String table) {
        return d.jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
