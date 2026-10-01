package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutineUpsertHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("auth.google-audience", () -> "integration-audience");
        r.add("auth.encryption-key", () -> key);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthCrypto crypto;
    @Autowired
    com.dragosfotache.gtbackend.auth.SessionAuthorization authorization;
    @Autowired
    com.dragosfotache.gtbackend.persistence.AccountWriteRepository writes;
    @Autowired
    com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository canonical;
    @Autowired
    com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository aggregates;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    private record Client(UUID account, String token) {
    }

    private Client client() {
        UUID account = UUID.randomUUID(), session = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", account);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", session, account);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '1 hour')", crypto.digest(token), session);
        return new Client(account, token);
    }

    private String uid() {
        return UUID.randomUUID().toString();
    }

    private String mutation(String mutationId, String root, String payload) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"routine.upsert\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,\"payload\":" + payload + "}";
    }

    private String envelope(String... mutations) {
        return "{\"mutations\":[" + String.join(",", mutations) + "]}";
    }

    private Response send(String token, String body) {
        try {
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sync/push")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record Response(int status, String body) {
    }

    @Test
    void aggregateImportsAndDeletesCoverAllRootKindsAndTombstoneReplay() {
        Client c = client();
        String w = uid(), r = uid(), x = uid();
        String timestamp = "2025-01-01T00:00:00Z";
        String wi = importMutation(uid(), "workout", w, "{\"id\":\"" + w + "\",\"name\":\"imported\",\"status\":\"active\",\"startedAt\":\"" + timestamp + "\",\"finishedAt\":null,\"exercises\":[]}");
        String ri = importMutation(uid(), "routine", r, "{\"id\":\"" + r + "\",\"name\":\"\",\"isDraft\":false,\"exercises\":[]}");
        String xi = importMutation(uid(), "customExercise", x, "{\"id\":\"" + x + "\",\"name\":\"Imported\"}");
        Response imported = send(c.token, envelope(wi, ri, xi));
        assertEquals(200, imported.status, imported.body);
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is null", Integer.class, UUID.fromString(w)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and is_draft=false and deleted_at is null", Integer.class, UUID.fromString(r)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercises where id=? and is_user_created and deleted_at is null", Integer.class, UUID.fromString(x)));
        String rootCollision = send(c.token, envelope(importMutation(uid(), "routine", r, "{\"id\":\"" + r + "\",\"name\":\"overwrite\",\"isDraft\":false,\"exercises\":[]}"))).body;
        assertTrue(rootCollision.contains("id_collision"), rootCollision);
        assertEquals("", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(r)));

        for (String[] target : List.of(new String[]{"workout", w}, new String[]{"routine", r}, new String[]{"customExercise", x})) {
            String mid = uid();
            String del = deleteMutation(mid, target[0], target[1]);
            Response first = send(c.token, envelope(del));
            assertEquals(200, first.status, first.body);
            assertTrue(first.body.contains("\"operation\":\"delete\""), first.body);
            Response retry = send(c.token, envelope(del));
            assertTrue(retry.body.contains("\"status\":\"duplicate\""));
            String table = target[0].equals("workout") ? "workouts" : target[0].equals("routine") ? "routines" : "exercises";
            String deletedAt = jdbc.queryForObject("select deleted_at::text from " + table + " where id=?", String.class, UUID.fromString(target[1]));
            String second = send(c.token, envelope(deleteMutation(uid(), target[0], target[1]))).body;
            assertTrue(second.contains("\"status\":\"accepted\""), second);
            assertEquals(3, jdbc.queryForObject("select revision from " + table + " where id=?", Integer.class, UUID.fromString(target[1])));
            assertTrue(jdbc.queryForObject("select deleted_at is not null from " + table + " where id=?", Boolean.class, UUID.fromString(target[1])));
            assertEquals(deletedAt, jdbc.queryForObject("select deleted_at::text from " + table + " where id=?", String.class, UUID.fromString(target[1])));
        }
    }

    @Test
    void workoutImportOptionalAndRequiredFinishedAtCasesAreDurableAndAtomic() {
        Client c = client();
        String activeMissing = uid(), activeNull = uid();
        String timestamp = "2025-01-01T00:00:00Z";
        Response active = send(c.token, envelope(
                importMutation(uid(), "workout", activeMissing, "{\"id\":\"" + activeMissing + "\",\"name\":\"a\",\"status\":\"active\",\"startedAt\":\"" + timestamp + "\",\"exercises\":[]}"),
                importMutation(uid(), "workout", activeNull, "{\"id\":\"" + activeNull + "\",\"name\":\"b\",\"status\":\"active\",\"startedAt\":\"" + timestamp + "\",\"finishedAt\":null,\"exercises\":[]}")));
        assertEquals(200, active.status, active.body);
        assertTrue(active.body.contains("accepted"));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and finished_at is null", Integer.class, UUID.fromString(activeMissing)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and finished_at is null", Integer.class, UUID.fromString(activeNull)));

        long cursorBeforeInvalids = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        for (String finishedAt : new String[]{"", ",\"finishedAt\":null"}) {
            String id = uid(), mid = uid();
            String record = "{\"id\":\"" + id + "\",\"name\":\"bad\",\"status\":\"finished\",\"startedAt\":\"" + timestamp + "\"" + finishedAt + ",\"exercises\":[]}";
            String raw = importMutation(mid, "workout", id, record);
            String response = send(c.token, envelope(raw)).body;
            assertTrue(response.contains("validation_error"), response);
            assertTrue(send(c.token, envelope(raw)).body.contains("\"status\":\"duplicate\""));
            assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(id)));
            assertEquals(cursorBeforeInvalids, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account));
        }
    }

    @Test
    void importedFinishedWorkoutPreservesNestedSnapshotAndActiveImportReplacesInOrderedBatch() throws Exception {
        Client c = client();
        UUID bundled = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class);
        String old = uid(), active = uid(), finished = uid(), entry = uid(), set = uid();
        String start = startMutation(uid(), old, "old active");
        assertTrue(send(c.token, envelope(start)).body.contains("accepted"));
        String ts = "2025-01-01T00:00:00Z", end = "2025-01-01T01:00:00Z";
        String nested = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + bundled + "\",\"position\":0,\"notes\":\"history\",\"restDurationSeconds\":45,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":8,\"weightKg\":50.5,\"isCompleted\":true,\"restSeconds\":17}]}]";
        String importFinished = importMutation(uid(), "workout", finished, "{\"id\":\"" + finished + "\",\"name\":\"history\",\"status\":\"finished\",\"startedAt\":\"" + ts + "\",\"finishedAt\":\"" + end + "\",\"exercises\":" + nested + "}");
        String importActive = importMutation(uid(), "workout", active, "{\"id\":\"" + active + "\",\"name\":\"replacement\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[]}");
        var both = parse(send(c.token, envelope(importFinished, importActive)).body).path("results");
        assertEquals("accepted", both.get(0).path("status").asText());
        var changes = both.get(1).path("batch").path("changes");
        assertEquals(2, changes.size());
        assertEquals(old, changes.get(0).path("aggregateId").asText());
        assertEquals("delete", changes.get(0).path("operation").asText());
        assertEquals(active, changes.get(1).path("aggregateId").asText());
        assertEquals("upsert", changes.get(1).path("operation").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and status='finished' and finished_at=? and deleted_at is null", Integer.class, UUID.fromString(finished), java.sql.Timestamp.from(java.time.Instant.parse(end))));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and repetitions=8 and weight_kg=50.5 and is_completed and rest_seconds=17", Integer.class, UUID.fromString(set)));
    }

    @Test
    void customExerciseInUseByAllLiveAggregateStatesCannotBeDeleted() {
        Client c = client();
        String custom = uid();
        assertTrue(send(c.token, envelope(importMutation(uid(), "customExercise", custom, "{\"id\":\"" + custom + "\",\"name\":\"ref\"}"))).body.contains("accepted"));
        String ts = "2025-01-01T00:00:00Z", fin = "2025-01-01T01:00:00Z";
        String wActive = uid(), wFinished = uid(), rDraft = uid(), rSaved = uid();
        String activeEx = "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]";
        String doneEntry = uid(), doneSet = uid();
        String finishedEx = "[{\"id\":\"" + doneEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + doneSet + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]}]";
        java.util.function.BiFunction<String, Boolean, String> routineRecord = (id, draft) -> "{\"id\":\"" + id + "\",\"name\":\"R\",\"isDraft\":" + draft + ",\"exercises\":[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        Response roots = send(c.token, envelope(
                importMutation(uid(), "workout", wActive, "{\"id\":\"" + wActive + "\",\"name\":\"A\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":" + activeEx + "}"),
                importMutation(uid(), "workout", wFinished, "{\"id\":\"" + wFinished + "\",\"name\":\"F\",\"status\":\"finished\",\"startedAt\":\"" + ts + "\",\"finishedAt\":\"" + fin + "\",\"exercises\":" + finishedEx + "}"),
                importMutation(uid(), "routine", rDraft, routineRecord.apply(rDraft, true)),
                importMutation(uid(), "routine", rSaved, routineRecord.apply(rSaved, false))));
        assertEquals(200, roots.status, roots.body);
        String blocked = send(c.token, envelope(deleteMutation(uid(), "customExercise", custom))).body;
        assertTrue(blocked.contains("exercise_in_use"), blocked);
        assertEquals(0, jdbc.queryForObject("select count(*) from exercises where id=? and deleted_at is not null", Integer.class, UUID.fromString(custom)));
        for (String[] t : List.of(new String[]{"workout", wActive}, new String[]{"workout", wFinished}, new String[]{"routine", rDraft}, new String[]{"routine", rSaved}))
            assertTrue(send(c.token, envelope(deleteMutation(uid(), t[0], t[1]))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(deleteMutation(uid(), "customExercise", custom))).body.contains("accepted"));
    }

    @Test
    void importsRespectDependencyOrderDeletedReferencesAndChildReservations() {
        Client c = client();
        String custom = uid(), workout = uid(), entry = uid(), set = uid(), timestamp = "2025-01-01T00:00:00Z";
        String record = "{\"id\":\"" + workout + "\",\"name\":\"W\",\"status\":\"active\",\"startedAt\":\"" + timestamp + "\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"isCompleted\":false}]}]}";
        String rejected = importMutation(uid(), "workout", workout, record);
        String created = importMutation(uid(), "customExercise", custom, "{\"id\":\"" + custom + "\",\"name\":\"dep\"}");
        var ordered = parse(send(c.token, envelope(rejected, created)).body).path("results");
        assertEquals("invalid_reference", ordered.get(0).path("errorCode").asText());
        assertEquals("accepted", ordered.get(1).path("status").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(workout)));
        assertTrue(send(c.token, envelope(rejected)).body.contains("\"status\":\"duplicate\""));
        assertTrue(send(c.token, envelope(deleteMutation(uid(), "customExercise", custom))).body.contains("accepted"));
        String deletedRef = uid();
        String deletedRecord = record.replace(workout, deletedRef);
        String deletedResponse = send(c.token, envelope(importMutation(uid(), "workout", deletedRef, deletedRecord))).body;
        assertTrue(deletedResponse.contains("invalid_reference"), deletedResponse);

        String seeded = uid();
        String seedRecord = record.replace(workout, seeded).replace(custom, "00000000-0000-0000-0000-000000000000");
        String validRef = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        seedRecord = seedRecord.replace("00000000-0000-0000-0000-000000000000", validRef);
        assertTrue(send(c.token, envelope(importMutation(uid(), "workout", seeded, seedRecord))).body.contains("accepted"));
        String collision = uid();
        // Reuse the already-reserved entry/set IDs while keeping a new root ID.
        String childCollision = "{\"id\":\"" + collision + "\",\"name\":\"collision\",\"status\":\"active\",\"startedAt\":\"" + timestamp + "\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + validRef + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"isCompleted\":false}]}]}";
        String result = send(c.token, envelope(importMutation(uid(), "workout", collision, childCollision))).body;
        assertTrue(result.contains("id_collision"), result);
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(collision)));
    }

    @Test
    void aggregateImportAndDeleteAcceptedAndRejectedOutcomesReplayAfterSecondHttpProcess() throws Exception {
        Client c = client();
        String routine = uid(), imported = importMutation(uid(), "routine", routine, "{\"id\":\"" + routine + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}");
        String missingDelete = deleteMutation(uid(), "workout", uid());
        int processPort = freePort();
        Process first = startServer(processPort);
        String committed, rejected;
        try {
            awaitServer(processPort);
            committed = sendAt(processPort, c.token, envelope(imported));
            rejected = sendAt(processPort, c.token, envelope(missingDelete));
            assertTrue(committed.contains("accepted"));
            assertTrue(rejected.contains("not_found"));
        } finally {
            stopServer(first);
        }
        Process second = startServer(processPort);
        try {
            awaitServer(processPort);
            assertTrue(sendAt(processPort, c.token, envelope(imported)).contains("duplicate"));
            assertTrue(sendAt(processPort, c.token, envelope(missingDelete)).contains("duplicate"));
            assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(routine)));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        } finally {
            stopServer(second);
        }
    }

    @Test
    void aggregateImportAndDeletePostWriteFaultsRollbackDomainRevisionBatchAndEvidence() {
        Client c = client();
        String imported = uid(), ts = "2025-01-01T00:00:00Z";
        String rawImport = importMutation(uid(), "workout", imported, "{\"id\":\"" + imported + "\",\"name\":\"x\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[]}");
        String marker = "aggregate_5f_fault";
        jdbc.execute("create function test_aggregate_5f_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger test_aggregate_5f_fault before insert on sync_change_batches for each row execute function test_aggregate_5f_fault()");
        try {
            assertEquals(500, send(c.token, envelope(rawImport)).status);
            assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(imported)));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        } finally {
            jdbc.execute("drop trigger test_aggregate_5f_fault on sync_change_batches");
            jdbc.execute("drop function test_aggregate_5f_fault()");
        }
        assertTrue(send(c.token, envelope(rawImport)).body.contains("accepted"));
        String delete = deleteMutation(uid(), "workout", imported);
        jdbc.execute("create function test_aggregate_5f_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger test_aggregate_5f_fault before insert on sync_change_batches for each row execute function test_aggregate_5f_fault()");
        try {
            assertEquals(500, send(c.token, envelope(delete)).status);
            assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is null and revision=1", Integer.class, UUID.fromString(imported)));
        } finally {
            jdbc.execute("drop trigger test_aggregate_5f_fault on sync_change_batches");
            jdbc.execute("drop function test_aggregate_5f_fault()");
        }
        assertTrue(send(c.token, envelope(delete)).body.contains("accepted"));
    }

    @Test
    void concurrentCreateOnlyImportsForSameRootSerializeToOneWinner() throws Exception {
        Client c = client();
        String id = uid(), ts = "2025-01-01T00:00:00Z";
        String a = importMutation(uid(), "workout", id, "{\"id\":\"" + id + "\",\"name\":\"a\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[]}");
        String b = importMutation(uid(), "workout", id, "{\"id\":\"" + id + "\",\"name\":\"b\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[]}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Response> one = pool.submit(() -> {
                gate.await();
                return send(c.token, envelope(a));
            });
            Future<Response> two = pool.submit(() -> {
                gate.await();
                return send(c.token, envelope(b));
            });
            gate.countDown();
            String combined = one.get(30, TimeUnit.SECONDS).body + two.get(30, TimeUnit.SECONDS).body;
            assertEquals(1, combined.split("accepted", -1).length - 1, combined);
            assertEquals(1, combined.split("id_collision", -1).length - 1, combined);
            assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(id)));
            assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(id)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aggregateDeleteAndImportNeverDiscloseOrClaimOtherAccountsRootsAndReferences() {
        Client owner = client(), attacker = client();
        String bw = uid(), br = uid(), bx = uid(), ts = "2025-01-01T00:00:00Z";
        String bootstrap = envelope(
                importMutation(uid(), "workout", bw, "{\"id\":\"" + bw + "\",\"name\":\"B workout\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[]}"),
                importMutation(uid(), "routine", br, "{\"id\":\"" + br + "\",\"name\":\"B routine\",\"isDraft\":true,\"exercises\":[]}"),
                importMutation(uid(), "customExercise", bx, "{\"id\":\"" + bx + "\",\"name\":\"B private\"}"));
        assertTrue(send(owner.token, bootstrap).body.contains("accepted"));
        Map<String, String> tables = Map.of("workout", "workouts", "routine", "routines", "customExercise", "exercises");
        Map<String, Integer> revisions = new HashMap<>();
        for (var root : List.of(new String[]{"workout", bw}, new String[]{"routine", br}, new String[]{"customExercise", bx})) {
            String table = tables.get(root[0]);
            revisions.put(root[1], jdbc.queryForObject("select revision from " + table + " where id=?", Integer.class, UUID.fromString(root[1])));
            String denial = send(attacker.token, envelope(deleteMutation(uid(), root[0], root[1]))).body;
            assertTrue(denial.contains("not_found"), denial);
            assertEquals(owner.account, jdbc.queryForObject("select owner_id from " + table + " where id=?", UUID.class, UUID.fromString(root[1])));
        }
        String reservedRoot = send(attacker.token, envelope(importMutation(uid(), "customExercise", bx, "{\"id\":\"" + bx + "\",\"name\":\"claim\"}"))).body;
        assertTrue(reservedRoot.contains("id_collision"), reservedRoot);
        String foreignWorkout = uid(), foreignRoutine = uid();
        String workoutRecord = "{\"id\":\"" + foreignWorkout + "\",\"name\":\"ref\",\"status\":\"active\",\"startedAt\":\"" + ts + "\",\"exercises\":[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + bx + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]}";
        String routineRecord = "{\"id\":\"" + foreignRoutine + "\",\"name\":\"ref\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + bx + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        String badWorkout = importMutation(uid(), "workout", foreignWorkout, workoutRecord);
        String badRoutine = importMutation(uid(), "routine", foreignRoutine, routineRecord);
        var refs = parse(send(attacker.token, envelope(badWorkout, badRoutine)).body).path("results");
        assertEquals("invalid_reference", refs.get(0).path("errorCode").asText());
        assertEquals("invalid_reference", refs.get(1).path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(foreignWorkout)));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(foreignRoutine)));
        for (var root : List.of(new String[]{"workout", bw}, new String[]{"routine", br}, new String[]{"customExercise", bx}))
            assertEquals(revisions.get(root[1]), jdbc.queryForObject("select revision from " + tables.get(root[0]) + " where id=?", Integer.class, UUID.fromString(root[1])));

        String tombstone = uid();
        assertTrue(send(attacker.token, envelope(importMutation(uid(), "routine", tombstone, "{\"id\":\"" + tombstone + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}"))).body.contains("accepted"));
        assertTrue(send(attacker.token, envelope(deleteMutation(uid(), "routine", tombstone))).body.contains("accepted"));
        String collision = importMutation(uid(), "routine", tombstone, "{\"id\":\"" + tombstone + "\",\"name\":\"resurrect\",\"isDraft\":true,\"exercises\":[]}");
        assertTrue(send(attacker.token, envelope(collision)).body.contains("id_collision"));
        assertTrue(send(attacker.token, envelope(collision)).body.contains("\"status\":\"duplicate\""));
        assertTrue(jdbc.queryForObject("select deleted_at is not null from routines where id=?", Boolean.class, UUID.fromString(tombstone)));
    }

    @Test
    void bundledExerciseDeleteIsReadOnly() {
        Client c = client();
        UUID bundled = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class);
        String response = send(c.token, envelope(deleteMutation(uid(), "customExercise", bundled.toString()))).body;
        assertTrue(response.contains("read_only"), response);
    }

    private String importMutation(String mutationId, String type, String id, String record) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"aggregate.import\",\"aggregateType\":\"" + type + "\",\"aggregateId\":\"" + id + "\",\"localGeneration\":1,\"payload\":{\"record\":" + record + "}}";
    }

    private String deleteMutation(String mutationId, String type, String id) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"aggregate.delete\",\"aggregateType\":\"" + type + "\",\"aggregateId\":\"" + id + "\",\"localGeneration\":1,\"payload\":{}}";
    }

    @Test
    void routineUpsertIsAtomicReplayableAndRoutineTransitionsAreEnforced() {
        Client c = client();
        String root = uid(), mid = uid();
        String draft = "{\"id\":\"" + root + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}";
        String raw = mutation(mid, root, draft);
        Response first = send(c.token, envelope(raw));
        assertEquals(200, first.status, first.body);
        assertTrue(first.body.contains("\"status\":\"accepted\""));
        assertTrue(first.body.contains("\"revision\":1"));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and owner_id=?", Integer.class, UUID.fromString(root), c.account));
        Response replay = send(c.token, envelope(raw));
        assertTrue(replay.body.contains("\"status\":\"duplicate\""));
        assertTrue(replay.body.contains("\"batchId\":"));
        assertTrue(replay.body.contains("\"cursor\":"));
        String cursor = first.body.replaceAll("(?s).*\\\"cursor\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertEquals(43, cursor.length());
        assertFalse(cursor.contains(c.account.toString()));
        assertTrue(replay.body.contains("\"cursor\":\"" + cursor + "\""));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=1", Integer.class, c.account));
        String winner = "{ \"id\" : \"" + root + "\", \"name\" : \"later\", \"isDraft\" : true, \"exercises\" : [] }";
        Response conflict = send(c.token, envelope(mutation(mid, root, winner)));
        assertEquals(200, conflict.status);
        assertTrue(conflict.body.contains("mutation_id_reused"));
        assertEquals("", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        String second = uid();
        Response edited = send(c.token, envelope(mutation(second, root, "{\"id\":\"" + root + "\",\"name\":\"edited\",\"isDraft\":true,\"exercises\":[]}")));
        assertTrue(edited.body.contains("\"revision\":2"));
        assertEquals("edited", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        Response oldRetry = send(c.token, envelope(raw));
        assertTrue(oldRetry.body.contains("\"status\":\"duplicate\""));
        assertTrue(oldRetry.body.contains("\"revision\":1"));
        assertEquals("edited", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        String promote = uid();
        Response bad = send(c.token, envelope(mutation(promote, root, "{\"id\":\"" + root + "\",\"name\":\"Saved\",\"isDraft\":false,\"exercises\":[]}")));
        assertTrue(bad.body.contains("invalid_state"));
        assertEquals(2, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
    }

    @Test
    void deleteMissingRootIsDurablyRejectedAndExactRawInnerBytesAreCompared() {
        Client c = client();
        String root = uid(), id = uid();
        String d = "{\"id\":\"" + root + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}";
        String supported = mutation(id, root, d);
        String otherId = uid(), workoutId = uid();
        String other = "{\"mutationId\":\"" + otherId + "\",\"type\":\"aggregate.delete\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":1,\"payload\":{}}";
        Response unavailable = send(c.token, envelope(supported, other));
        assertEquals(200, unavailable.status);
        assertTrue(unavailable.body.contains("not_found"));
        assertTrue(unavailable.body.contains("accepted"));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(root)));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        Response accepted = send(c.token, envelope(supported));
        assertEquals(200, accepted.status);
        String innerWhitespace = supported.replace("\"name\":\"\"", "\"name\" : \"\"");
        assertTrue(send(c.token, envelope(innerWhitespace)).body.contains("mutation_id_reused"));
        String outer = "{ \"mutations\" : [ \n" + supported + " \t] }";
        assertTrue(send(c.token, outer).body.contains("\"status\":\"duplicate\""));
    }

    @Test
    void orderedRoutineBatchContinuesAfterDurableRejectionAndReplaysItAfterStateChanges() {
        Client c = client();
        String root = uid(), rejectId = uid(), createId = uid();
        String attempted = mutation(rejectId, root, "{\"id\":\"" + root + "\",\"name\":\"Saved\",\"isDraft\":false,\"exercises\":[]}");
        String create = mutation(createId, root, "{\"id\":\"" + root + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}");
        Response mixed = send(c.token, envelope(attempted, create));
        assertEquals(200, mixed.status, mixed.body);
        assertTrue(mixed.body.indexOf("invalid_state") < mixed.body.indexOf("accepted"));
        assertTrue(mixed.body.indexOf(rejectId) < mixed.body.indexOf(createId));
        assertEquals(1, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        Response retry = send(c.token, envelope(attempted));
        assertTrue(retry.body.contains("\"status\":\"duplicate\""));
        assertTrue(retry.body.contains("invalid_state"));
        assertEquals(1, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
    }

    @Test
    void invalidNestedReferenceRejectsWithoutAnyDomainOrBatchWritesAndLaterMutationCommits() {
        Client c = client();
        String rejectedRoot = uid(), goodRoot = uid(), badId = uid(), goodId = uid(), entry = uid(), exercise = uid();
        String invalidPayload = "{\"id\":\"" + rejectedRoot + "\",\"name\":\"bad\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        String validPayload = "{\"id\":\"" + goodRoot + "\",\"name\":\"good\",\"isDraft\":true,\"exercises\":[]}";
        Response result = send(c.token, envelope(mutation(badId, rejectedRoot, invalidPayload), mutation(goodId, goodRoot, validPayload)));
        assertEquals(200, result.status, result.body);
        assertTrue(result.body.contains("invalid_reference"), result.body);
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(rejectedRoot)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(goodRoot)));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
    }

    @Test
    void childIdentityConstraintFailureRollsBackToSavepointThenLaterMutationCommits() {
        Client c = client();
        String sourceRoot = uid(), entry = uid(), exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String seed = mutation(uid(), sourceRoot, "{\"id\":\"" + sourceRoot + "\",\"name\":\"seed\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}");
        assertEquals(200, send(c.token, envelope(seed)).status);
        String badRoot = uid(), goodRoot = uid();
        String bad = mutation(uid(), badRoot, "{\"id\":\"" + badRoot + "\",\"name\":\"bad\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}");
        String good = mutation(uid(), goodRoot, "{\"id\":\"" + goodRoot + "\",\"name\":\"good\",\"isDraft\":true,\"exercises\":[]}");
        Response result = send(c.token, envelope(bad, good));
        assertEquals(200, result.status, result.body);
        assertTrue(result.body.contains("id_collision"));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(badRoot)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(goodRoot)));
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        assertEquals(3, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
    }

    @Test
    void concurrentIdenticalMutationIdsSerializeToOneCanonicalCommit() {
        Client c = client();
        String root = uid(), mid = uid(), raw = mutation(mid, root, "{\"id\":\"" + root + "\",\"name\":\"race\",\"isDraft\":true,\"exercises\":[]}"), body = envelope(raw);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Response>> requests = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                requests.add(pool.submit(() -> {
                    gate.await();
                    return send(c.token, body);
                }));
            gate.countDown();
            Response one = requests.get(0).get(), two = requests.get(1).get();
            assertEquals(200, one.status);
            assertEquals(200, two.status);
            assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
            assertTrue(one.body.contains("batchId") && two.body.contains("batchId"));
            assertTrue(one.body.contains("\"status\":\"accepted\"") || two.body.contains("\"status\":\"accepted\""));
            assertTrue(one.body.contains("\"status\":\"duplicate\"") || two.body.contains("\"status\":\"duplicate\""));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void exactIdentityDistinguishesObjectKeyOrderAndNumberSpellingAndBatchCarriesCanonicalChildren() {
        Client c = client();
        String root = uid(), entry = uid(), set = uid(), exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString(), mid = uid();
        String expectedName = jdbc.queryForObject("select name from exercises where id=?", String.class, UUID.fromString(exercise));
        String payload = "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0.0}]}]}";
        String original = mutation(mid, root, payload);
        Response accepted = send(c.token, envelope(original));
        assertEquals(200, accepted.status, accepted.body);
        assertTrue(accepted.body.contains("\"aggregateType\":\"routine\""));
        assertTrue(accepted.body.contains("\"operation\":\"upsert\""));
        assertTrue(accepted.body.contains("\"exerciseName\":\"" + expectedName + "\""));
        assertTrue(accepted.body.contains("\"exerciseId\":\"" + exercise + "\""));
        assertTrue(accepted.body.contains("\"id\":\"" + entry + "\""));
        assertTrue(accepted.body.contains("\"id\":\"" + set + "\""));
        assertTrue(accepted.body.contains("\"repetitions\":null"));
        assertTrue(accepted.body.contains("\"weightKg\":0.0"));
        assertTrue(accepted.body.contains("\"revision\":1"));
        String reordered = original.replace("\"id\":\"" + root + "\",\"name\":\"R\"", "\"name\":\"R\",\"id\":\"" + root + "\"");
        assertTrue(send(c.token, envelope(reordered)).body.contains("mutation_id_reused"));
        String alternateNumber = original.replace("0.0}", "0.00}");
        assertTrue(send(c.token, envelope(alternateNumber)).body.contains("mutation_id_reused"));
    }

    @Test
    void durableReplaySurvivesExecutorReplacementAfterLostHttpResponse() {
        Client c = client();
        String root = uid(), mid = uid(), raw = mutation(mid, root, "{\"id\":\"" + root + "\",\"name\":\"persisted\",\"isDraft\":true,\"exercises\":[]}");
        Response first = send(c.token, envelope(raw));
        assertEquals(200, first.status); // Model loss of the response after commit.
        RoutineUpsertExecutor restarted = new RoutineUpsertExecutor(jdbc, authorization, writes, aggregates, txManager);
        List<Map<String, Object>> recovered = restarted.push(c.token, new PushPreflight().parse(envelope(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("duplicate", recovered.getFirst().get("status"));
        assertEquals("persisted", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
    }

    @Test
    void retryAfterRealHttpProcessRestartReplaysOriginalBatchAndCursor() throws Exception {
        Client c = client();
        String root = uid(), mid = uid(), raw = mutation(mid, root, "{\"id\":\"" + root + "\",\"name\":\"process-replay\",\"isDraft\":true,\"exercises\":[]}"), body = envelope(raw);
        int port = freePort();
        Process first = startServer(port);
        String committed;
        try {
            awaitServer(port);
            committed = sendAt(port, c.token, body);
            assertTrue(committed.contains("\"status\":\"accepted\""));
        } finally {
            stopServer(first);
        }
        Process second = startServer(port);
        try {
            awaitServer(port);
            String replay = sendAt(port, c.token, body);
            assertTrue(replay.contains("\"status\":\"duplicate\""));
            com.fasterxml.jackson.databind.JsonNode before = new com.fasterxml.jackson.databind.ObjectMapper().readTree(committed).path("results").get(0).path("batch"), after = new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay).path("results").get(0).path("batch");
            assertEquals(before, after);
            assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=? and mutation_id=?", Integer.class, c.account, UUID.fromString(mid)));
        } finally {
            stopServer(second);
        }
    }

    @Test
    void customExerciseLostResponseReplaysAcrossRealHttpProcessRestart() throws Exception {
        Client c = client();
        String id = uid(), mutationId = uid();
        UUID muscle = jdbc.queryForObject("select id from muscle_groups order by id limit 1", UUID.class);
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        String raw = customMutation(mutationId, id, "Restart Label", "\"primaryMuscleGroupId\":\"" + muscle + "\",\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":0,\"instructions\":[\"press\"]");
        String body = envelope(raw);
        int port = freePort();
        String committed;
        Process first = startServer(port);
        try {
            awaitServer(port);
            committed = sendAt(port, c.token, body);
            assertTrue(committed.contains("\"status\":\"accepted\""));
        } finally {
            stopServer(first);
        }
        Process second = startServer(port);
        try {
            awaitServer(port);
            String replay = sendAt(port, c.token, body);
            assertTrue(replay.contains("\"status\":\"duplicate\""));
            var before = parse(committed).path("results").get(0).path("batch");
            var after = parse(replay).path("results").get(0).path("batch");
            assertEquals(before, after);
            assertEquals("Restart Label", before.path("changes").get(0).path("record").path("name").asText());
            assertEquals(before.path("cursor").asText(), after.path("cursor").asText());
            assertEquals(1, jdbc.queryForObject("select revision from exercises where id=? and owner_id=?", Integer.class, UUID.fromString(id), c.account));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=? and mutation_id=?", Integer.class, c.account, UUID.fromString(mutationId)));
        } finally {
            stopServer(second);
        }
    }

    private int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Process startServer(int port) {
        try {
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp, "com.dragosfotache.gtbackend.GtBackendApplication", "--server.port=" + port);
            Map<String, String> env = pb.environment();
            env.put("DB_URL", pg.getJdbcUrl());
            env.put("DB_USER", pg.getUsername());
            env.put("DB_PASSWORD", pg.getPassword());
            env.put("AUTH_ENCRYPTION_KEY", key);
            env.put("GOOGLE_OAUTH_AUDIENCE", "integration-audience");
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return pb.start();
        } catch (Exception e) {
            throw new AssertionError("Unable to launch isolated HTTP process", e);
        }
    }

    private void awaitServer(int port) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sync/push")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401) return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("fresh HTTP process did not become ready");
    }

    private void stopServer(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "HTTP child process did not stop");
            }
            assertTrue(process.exitValue() == 0 || process.exitValue() == 143, "HTTP child process did not stop on requested termination");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String sendAt(int port, String token, String body) {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sync/push")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void unexpectedDatabaseFailureReturnsSanitizedInternalErrorNotRetryableUnavailable() {
        Client c = client();
        String marker = "private-trigger-diagnostic", root = uid(), raw = mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"must rollback\",\"isDraft\":true,\"exercises\":[]}");
        jdbc.execute("create function test_sync_push_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger test_sync_push_fault before insert on processed_mutations for each row execute function test_sync_push_fault()");
        try {
            Response failed = send(c.token, envelope(raw));
            assertEquals(500, failed.status, failed.body);
            assertEquals("{\"errorCode\":\"internal_error\"}", failed.body);
            assertFalse(failed.body.contains(marker));
            assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(0, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        } finally {
            jdbc.execute("drop trigger test_sync_push_fault on processed_mutations");
            jdbc.execute("drop function test_sync_push_fault()");
        }
    }

    @Test
    void customExerciseUpsertCreatesAndReplacesPrivateAggregateWithReplayBatch() {
        Client c = client();
        String root = uid(), mid = uid();
        String raw = "{\"mutationId\":\"" + mid + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + root + "\",\"name\":\"Private Row\",\"secondaryMuscleGroupIds\":[],\"instructions\":[]}}";
        Response first = send(c.token, envelope(raw));
        assertEquals(200, first.status, first.body);
        assertTrue(first.body.contains("\"status\":\"accepted\""));
        assertTrue(first.body.contains("\"aggregateType\":\"customExercise\""));
        assertEquals(1, jdbc.queryForObject("select revision from exercises where id=? and owner_id=?", Integer.class, UUID.fromString(root), c.account));
        assertTrue(send(c.token, envelope(raw)).body.contains("\"status\":\"duplicate\""));
        String updated = raw.replace("Private Row", "Renamed");
        String newMutation = updated.replace(mid, uid());
        Response second = send(c.token, envelope(newMutation));
        assertEquals(200, second.status, second.body);
        assertEquals("Renamed", jdbc.queryForObject("select name from exercises where id=? and owner_id=?", String.class, UUID.fromString(root), c.account));
        assertEquals(2, jdbc.queryForObject("select revision from exercises where id=? and owner_id=?", Integer.class, UUID.fromString(root), c.account));
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        Response oldReplay = send(c.token, envelope(raw));
        assertTrue(oldReplay.body.contains("\"status\":\"duplicate\""));
        assertEquals(parse(first.body).path("results").get(0).path("batch"), parse(oldReplay.body).path("results").get(0).path("batch"));
        assertTrue(oldReplay.body.contains("\"revision\":1"));
        assertEquals(2, jdbc.queryForObject("select revision from exercises where id=? and owner_id=?", Integer.class, UUID.fromString(root), c.account));
    }

    @Test
    void bundledExerciseMutationIsReadOnlyAndHasNoPersistentEffects() {
        Client c = client();
        UUID bundled = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class);
        String raw = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + bundled + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + bundled + "\",\"name\":\"tamper\"}}";
        Response result = send(c.token, envelope(raw));
        assertEquals(200, result.status, result.body);
        assertTrue(result.body.contains("read_only"), result.body);
        assertFalse(result.body.contains("SQLState"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        assertNotEquals("tamper", jdbc.queryForObject("select name from exercises where id=?", String.class, bundled));

        Client foreign = client();
        String foreignId = uid();
        String foreignCreate = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + foreignId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + foreignId + "\",\"name\":\"Owned elsewhere\"}}";
        assertEquals(200, send(foreign.token, envelope(foreignCreate)).status);
        String collision = customMutation(uid(), foreignId, "Overwrite", "\"secondaryMuscleGroupIds\":[]");
        Response collisionResult = send(c.token, envelope(collision));
        assertTrue(collisionResult.body.contains("id_collision"));
        assertFalse(collisionResult.body.contains(foreign.account.toString()));
        assertEquals("Owned elsewhere", jdbc.queryForObject("select name from exercises where id=?", String.class, UUID.fromString(foreignId)));

        String ownId = uid();
        String ownCreate = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + ownId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + ownId + "\",\"name\":\"Soon deleted\"}}";
        assertEquals(200, send(c.token, envelope(ownCreate)).status);
        jdbc.update("update exercises set deleted_at=clock_timestamp() where id=? and owner_id=?", UUID.fromString(ownId), c.account);
        String tombstoneWrite = customMutation(uid(), ownId, "Resurrect", "\"secondaryMuscleGroupIds\":[]");
        assertTrue(send(c.token, envelope(tombstoneWrite)).body.contains("id_collision"));
        assertEquals("Soon deleted", jdbc.queryForObject("select name from exercises where id=?", String.class, UUID.fromString(ownId)));
    }

    @Test
    void customRelationshipReplacementPreservesNullableQuantityAndDoesNotReviseReferencingRoots() {
        Client c = client();
        String customId = uid();
        UUID muscleA = jdbc.queryForObject("select id from muscle_groups order by id limit 1", UUID.class);
        UUID muscleB = jdbc.queryForObject("select id from muscle_groups order by id offset 1 limit 1", UUID.class);
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        UUID equipment2 = jdbc.queryForObject("select id from equipment where id<>? order by id limit 1", UUID.class, equipment);
        String initial = customMutation(uid(), customId, "Custom", "\"primaryMuscleGroupId\":\"" + muscleA + "\",\"secondaryMuscleGroupIds\":[\"" + muscleB + "\"],\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":null,\"category\":\"Cat\",\"difficultyLevel\":\"Hard\",\"forceType\":\"Push\",\"mechanics\":\"Compound\",\"instructions\":[\"one\"]");
        assertEquals(200, send(c.token, envelope(initial)).status);
        assertNull(jdbc.queryForObject("select num_items from exercise_equipment where exercise_id=?", Integer.class, UUID.fromString(customId)));
        UUID routineId = UUID.randomUUID(), routineEntry = UUID.randomUUID();
        String routine = mutation(uid(), routineId.toString(), "{\"id\":\"" + routineId + "\",\"name\":\"Draft\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + customId + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}");
        assertEquals(200, send(c.token, envelope(routine)).status);
        UUID workoutId = UUID.randomUUID(), workoutEntry = UUID.randomUUID(), workoutSet = UUID.randomUUID();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), workoutId.toString(), "Active"))).status);
        String workoutInput = "{\"id\":\"" + workoutId + "\",\"name\":\"Active\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + workoutEntry + "\",\"exerciseId\":\"" + customId + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + workoutSet + "\",\"position\":0,\"isCompleted\":false}]}]}";
        String workout = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":" + workoutInput + "}";
        assertEquals(200, send(c.token, envelope(workout)).status);
        int routineRevision = jdbc.queryForObject("select revision from routines where id=?", Integer.class, routineId);
        int workoutRevision = jdbc.queryForObject("select revision from workouts where id=?", Integer.class, workoutId);
        String replacement = customMutation(uid(), customId, "Renamed", "\"primaryMuscleGroupId\":null,\"secondaryMuscleGroupIds\":[\"" + muscleA + "\"],\"equipmentId\":\"" + equipment2 + "\",\"equipmentNumItems\":0,\"category\":null,\"difficultyLevel\":null,\"forceType\":null,\"mechanics\":null,\"instructions\":null");
        Response renamed = send(c.token, envelope(replacement));
        assertEquals(200, renamed.status, renamed.body);
        assertTrue(renamed.body.contains("\"name\":\"Renamed\""));
        assertEquals(0, jdbc.queryForObject("select num_items from exercise_equipment where exercise_id=?", Integer.class, UUID.fromString(customId)));
        assertEquals(equipment2, jdbc.queryForObject("select equipment_id from exercise_equipment where exercise_id=?", UUID.class, UUID.fromString(customId)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=?", Integer.class, UUID.fromString(customId)));
        assertNull(jdbc.queryForObject("select category from exercises where id=?", String.class, UUID.fromString(customId)));
        assertEquals(routineRevision, jdbc.queryForObject("select revision from routines where id=?", Integer.class, routineId));
        assertEquals(workoutRevision, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, workoutId));
        assertEquals("Renamed", canonical.readRoutine(c.account, routineId).exercises().getFirst().exerciseName());
        assertEquals("Renamed", canonical.readWorkout(c.account, workoutId).exercises().getFirst().exerciseName());
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_changes where account_id=? and cursor=(select committed_cursor from account_sync_state where account_id=?)", Integer.class, c.account, c.account));
    }

    private String customMutation(String mutationId, String id, String name, String properties) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + id + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\"," + properties + "}}";
    }

    @Test
    void routineSavePromotesOnlyEligibleOwnedRoutineAndReplaysCanonicalRevision() {
        Client c = client();
        String root = uid(), entry = uid(), exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String create = mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}");
        assertEquals(200, send(c.token, envelope(create)).status);
        String saveId = uid();
        String save = "{\"mutationId\":\"" + saveId + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + root + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Saved\"}}";
        Response accepted = send(c.token, envelope(save));
        assertEquals(200, accepted.status, accepted.body);
        assertTrue(accepted.body.contains("\"isDraft\":false"));
        assertEquals("Saved", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(2, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
        assertTrue(send(c.token, envelope(save)).body.contains("\"status\":\"duplicate\""));
        String updateSaved = save.replace(saveId, uid()).replace("Saved", "Updated");
        Response updated = send(c.token, envelope(updateSaved));
        assertTrue(updated.body.contains("accepted"));
        assertEquals("Updated", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(3, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
        Client other = client();
        // Use a real foreign root ID, created by the second account.
        String foreignId = uid();
        String foreignDraft = mutation(uid(), foreignId, "{\"id\":\"" + foreignId + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}");
        assertEquals(200, send(other.token, envelope(foreignDraft)).status);
        String foreignSave = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + foreignId + "\",\"localGeneration\":2,\"payload\":{\"name\":\"No leak\"}}";
        assertTrue(send(c.token, envelope(foreignSave)).body.contains("not_found"));
        String missingSave = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + uid() + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Missing\"}}";
        assertTrue(send(c.token, envelope(missingSave)).body.contains("not_found"));
    }

    @Test
    void addExercisesUsesDeterministicFinishedHistoryAndFallbackAndReplaysOriginalBatch() {
        Client c = client();
        List<UUID> exerciseIds = jdbc.query("select id from exercises where owner_id is null order by id limit 2", (rs, n) -> rs.getObject(1, UUID.class));
        String exercise = exerciseIds.get(0).toString(), fallbackExercise = exerciseIds.get(1).toString();
        String target = uid(), baseEntry = uid(), baseSet = uid();
        String low = "7fffffff-ffff-4fff-8fff-fffffffffff0", high = "7fffffff-ffff-4fff-8fff-fffffffffff1";
        String lowE = uid(), lowS = uid(), highE0 = uid(), highE1 = uid(), highS0 = uid(), highS1 = uid(), highS2 = uid();
        for (String historyRoot : List.of(low, high)) {
            assertTrue(send(c.token, envelope(startMutation(uid(), historyRoot, "history"))).body.contains("accepted"));
            String es;
            if (historyRoot.equals(low))
                es = "[{\"id\":\"" + lowE + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"low\",\"restDurationSeconds\":11,\"sets\":[{\"id\":\"" + lowS + "\",\"position\":0,\"repetitions\":2,\"weightKg\":20,\"isCompleted\":true}]}]";
            else
                es = "[{\"id\":\"" + highE0 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"chosen\",\"restDurationSeconds\":77,\"sets\":[{\"id\":\"" + highS0 + "\",\"position\":0,\"repetitions\":8,\"weightKg\":50,\"isCompleted\":true},{\"id\":\"" + highS1 + "\",\"position\":1,\"repetitions\":9,\"weightKg\":55,\"isCompleted\":true}]},{\"id\":\"" + highE1 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"notes\":\"later occurrence\",\"restDurationSeconds\":44,\"sets\":[{\"id\":\"" + highS2 + "\",\"position\":0,\"repetitions\":99,\"weightKg\":99,\"isCompleted\":true}]}]";
            assertTrue(send(c.token, envelope(workoutUpsert(uid(), historyRoot, es))).body.contains("accepted"));
            assertTrue(send(c.token, envelope(finishMutation(uid(), historyRoot))).body.contains("accepted"));
        }
        assertEquals(jdbc.queryForObject("select finished_at from workouts where id=?", java.sql.Timestamp.class, UUID.fromString(low)), jdbc.queryForObject("select finished_at from workouts where id=?", java.sql.Timestamp.class, UUID.fromString(high)));
        assertTrue(send(c.token, envelope(startMutation(uid(), target, "active target"))).body.contains("accepted"));
        String base = "[{\"id\":\"" + baseEntry + "\",\"exerciseId\":\"" + fallbackExercise + "\",\"position\":0,\"notes\":\"keep\",\"restDurationSeconds\":13,\"sets\":[{\"id\":\"" + baseSet + "\",\"position\":0,\"repetitions\":3,\"weightKg\":4,\"isCompleted\":false}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), target, base))).body.contains("accepted"));
        String added0 = uid(), added1 = uid(), added2 = uid(), a00 = uid(), a01 = uid(), fallbackSet = uid(), a20 = uid(), a21 = uid(), addMutation = uid();
        String additions = "[{\"id\":\"" + added0 + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + a00 + "\",\"" + a01 + "\"]},{\"id\":\"" + added1 + "\",\"exerciseId\":\"" + fallbackExercise + "\",\"setIds\":[\"" + fallbackSet + "\"]},{\"id\":\"" + added2 + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + a20 + "\",\"" + a21 + "\"]}]";
        String request = "{\"mutationId\":\"" + addMutation + "\",\"type\":\"workout.addExercises\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + target + "\",\"localGeneration\":3,\"payload\":{\"additions\":" + additions + "}}";
        Response response = send(c.token, envelope(request));
        assertEquals(200, response.status, response.body);
        assertTrue(response.body.contains("\"status\":\"accepted\""), response.body);
        assertTrue(response.body.contains("\"restDurationSeconds\":77"));
        assertTrue(response.body.contains("\"repetitions\":8"));
        assertTrue(response.body.contains("\"weightKg\":50.0"));
        assertTrue(response.body.contains("\"restDurationSeconds\":30"));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and workout_exercise_id=? and position=0 and repetitions=8 and weight_kg=50 and not is_completed and rest_seconds is null", Integer.class, UUID.fromString(a00), UUID.fromString(added0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and workout_exercise_id=? and position=1 and repetitions=9 and weight_kg=55 and not is_completed and rest_seconds is null", Integer.class, UUID.fromString(a01), UUID.fromString(added0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and workout_exercise_id=? and position=0 and repetitions=8 and weight_kg=50 and not is_completed and rest_seconds is null", Integer.class, UUID.fromString(a20), UUID.fromString(added2)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and workout_exercise_id=? and position=1 and repetitions=9 and weight_kg=55 and not is_completed and rest_seconds is null", Integer.class, UUID.fromString(a21), UUID.fromString(added2)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and workout_exercise_id=? and position=0 and repetitions is null and weight_kg is null and rest_seconds is null and not is_completed", Integer.class, UUID.fromString(fallbackSet), UUID.fromString(added1)));
        assertEquals(4, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(target)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where id=? and position=0 and notes='keep' and rest_duration_seconds=13", Integer.class, UUID.fromString(baseEntry)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where id=? and position=1 and rest_duration_seconds=77", Integer.class, UUID.fromString(added0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where id=? and position=2 and rest_duration_seconds=30", Integer.class, UUID.fromString(added1)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where id=? and position=3 and rest_duration_seconds=77", Integer.class, UUID.fromString(added2)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_sets where id in (?,?,?,?,?) and (is_completed or rest_seconds is not null)", Integer.class, UUID.fromString(a00), UUID.fromString(a01), UUID.fromString(fallbackSet), UUID.fromString(a20), UUID.fromString(a21)));
        assertEquals(3, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals(1, parse(response.body).path("results").get(0).path("batch").path("changes").size());
        assertEquals("upsert", parse(response.body).path("results").get(0).path("batch").path("changes").get(0).path("operation").asText());
        String newer = uid(), newerEntry = uid(), newerSet = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), newer, "later history"))).body.contains("accepted"));
        String newerInput = "[{\"id\":\"" + newerEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"newer\",\"restDurationSeconds\":120,\"sets\":[{\"id\":\"" + newerSet + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), newer, newerInput))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), newer))).body.contains("accepted"));
        Response replay = send(c.token, envelope(request));
        assertTrue(replay.body.contains("duplicate"));
        assertEquals(parse(response.body).path("results").get(0).path("batch"), parse(replay.body).path("results").get(0).path("batch"));
    }

    @Test
    void addExercisesRejectsShapeAndReferenceErrorsAndHonorsRemovedChildReservations() {
        Client a = client(), b = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), root, "active"))).body.contains("accepted"));
        String entry = uid(), set = uid(), mid = uid();
        String wrongShape = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"setIds\":[\"" + set + "\",\"" + uid() + "\"]}]";
        String shape = addMutation(mid, root, wrongShape);
        var result = parse(send(a.token, envelope(shape)).body).path("results").get(0);
        assertEquals("history_shape_changed", result.path("errorCode").asText());
        UUID custom = UUID.fromString(uid());
        assertTrue(send(b.token, envelope(customMutation(uid(), custom.toString(), "Private", "\"secondaryMuscleGroupIds\":[]"))).body.contains("accepted"));
        String foreignAdd = "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + custom + "\",\"setIds\":[\"" + uid() + "\"]}]";
        assertTrue(send(a.token, envelope(addMutation(uid(), root, foreignAdd))).body.contains("invalid_reference"));
        String unknownAdd = "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + uid() + "\",\"setIds\":[\"" + uid() + "\"]}]";
        assertTrue(send(a.token, envelope(addMutation(uid(), root, unknownAdd))).body.contains("invalid_reference"));
        assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(root)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(root)));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, a.account));

        String removed = uid();
        String initial = "[{\"id\":\"" + removed + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(a.token, envelope(workoutUpsert(uid(), root, initial))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(childMutation(true, false, uid(), root, removed, null))).body.contains("accepted"));
        String reused = "[{\"id\":\"" + removed + "\",\"exerciseId\":\"" + ex + "\",\"setIds\":[\"" + uid() + "\"]}]";
        assertTrue(send(a.token, envelope(addMutation(uid(), root, reused))).body.contains("id_collision"));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where id=?", Integer.class, UUID.fromString(removed)));
    }

    @Test
    void addExercisesRejectsWhenFinishedHistoryShapeChangesAfterPlanningWithoutEffects() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String planned = "00000000-0000-4000-8000-000000000010", plannedEntry = uid(), plannedSet = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), planned, "planned history"))).body.contains("accepted"));
        String oneSet = "[{\"id\":\"" + plannedEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":20,\"sets\":[{\"id\":\"" + plannedSet + "\",\"position\":0,\"repetitions\":5,\"weightKg\":25,\"isCompleted\":true}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), planned, oneSet))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), planned))).body.contains("accepted"));
        String target = uid();
        String plannedEntryId = uid(), plannedSetId = uid(), mutationId = uid();
        String request = addMutation(mutationId, target, "[{\"id\":\"" + plannedEntryId + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + plannedSetId + "\"]}]");
        String newer = "7fffffff-ffff-4fff-8fff-fffffffffff2", newerEntry = uid(), newerSet0 = uid(), newerSet1 = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), newer, "newer history"))).body.contains("accepted"));
        String twoSets = "[{\"id\":\"" + newerEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + newerSet0 + "\",\"position\":0,\"repetitions\":6,\"weightKg\":30,\"isCompleted\":true},{\"id\":\"" + newerSet1 + "\",\"position\":1,\"repetitions\":7,\"weightKg\":35,\"isCompleted\":true}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), newer, twoSets))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), newer))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(startMutation(uid(), target, "target"))).body.contains("accepted"));
        int revision = jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(target));
        int batches = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        var result = parse(send(c.token, envelope(request)).body).path("results").get(0);
        assertEquals("history_shape_changed", result.path("errorCode").asText());
        assertEquals(revision, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(target)));
        assertEquals(batches, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
    }

    @Test
    void addExercisesRequiresOwnedActiveTargetAndConcurrentAddsSerialize() throws Exception {
        Client a = client(), b = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String target = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), target, "active"))).body.contains("accepted"));
        String stale = addMutation(uid(), uid(), "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + uid() + "\"]}]");
        assertTrue(send(a.token, envelope(stale)).body.contains("not_found"));
        String foreign = addMutation(uid(), target, "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + uid() + "\"]}]");
        assertTrue(send(b.token, envelope(foreign)).body.contains("not_found"));
        String entry = uid(), set = uid();
        String first = addMutation(uid(), target, "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + set + "\"]}]");
        String otherEntry = uid(), otherSet = uid();
        String second = addMutation(uid(), target, "[{\"id\":\"" + otherEntry + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + otherSet + "\"]}]");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Response> one = pool.submit(() -> {
                gate.await();
                return send(a.token, envelope(first));
            });
            Future<Response> two = pool.submit(() -> {
                gate.await();
                return send(a.token, envelope(second));
            });
            gate.countDown();
            assertTrue(one.get(30, TimeUnit.SECONDS).body.contains("accepted"));
            assertTrue(two.get(30, TimeUnit.SECONDS).body.contains("accepted"));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(target)));
        assertEquals(3, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals(3, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, a.account));
        String finished = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), finished, "will finish"))).body.contains("accepted"));
        String done = "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + uid() + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]}]";
        assertTrue(send(a.token, envelope(workoutUpsert(uid(), finished, done))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(finishMutation(uid(), finished))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(addMutation(uid(), finished, "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + uid() + "\"]}]"))).body.contains("invalid_state"));
    }

    @Test
    void addExercisesFaultRollsBackRowsRevisionBatchAndReplayEvidence() {
        Client c = client();
        String root = uid(), exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        assertTrue(send(c.token, envelope(startMutation(uid(), root, "active"))).body.contains("accepted"));
        int revision = jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(root));
        String request = addMutation(uid(), root, "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + uid() + "\"]}]");
        String marker = "5e_add_fault";
        jdbc.execute("create function test_5e_add_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger test_5e_add_fault before insert on sync_change_batches for each row execute function test_5e_add_fault()");
        try {
            Response failed = send(c.token, envelope(request));
            assertEquals(500, failed.status);
            assertFalse(failed.body.contains(marker));
            assertEquals(revision, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Integer.class, c.account));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where mutation_id=?", Integer.class, UUID.fromString(parseMutationId(request))));
        } finally {
            jdbc.execute("drop trigger test_5e_add_fault on sync_change_batches");
            jdbc.execute("drop function test_5e_add_fault()");
        }
        assertTrue(send(c.token, envelope(request)).body.contains("accepted"));
    }

    @Test
    void addExercisesReplaySurvivesActualHttpProcessRestart() throws Exception {
        Client c = client();
        String root = uid(), exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        assertTrue(send(c.token, envelope(startMutation(uid(), root, "active"))).body.contains("accepted"));
        String request = addMutation(uid(), root, "[{\"id\":\"" + uid() + "\",\"exerciseId\":\"" + exercise + "\",\"setIds\":[\"" + uid() + "\"]}]");
        String body = envelope(request);
        int port = freePort();
        String original;
        Process first = startServer(port);
        try {
            awaitServer(port);
            original = sendAt(port, c.token, body);
            assertTrue(original.contains("accepted"));
        } finally {
            stopServer(first);
        }
        Process second = startServer(port);
        try {
            awaitServer(port);
            String replay = sendAt(port, c.token, body);
            assertTrue(replay.contains("duplicate"));
            assertEquals(parse(original).path("results").get(0).path("batch"), parse(replay).path("results").get(0).path("batch"));
            assertEquals(2, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where mutation_id=?", Integer.class, UUID.fromString(parseMutationId(request))));
        } finally {
            stopServer(second);
        }
    }

    private String addMutation(String mutationId, String root, String additions) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"workout.addExercises\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":4,\"payload\":{\"additions\":" + additions + "}}";
    }

    @Test
    void saveAsRoutineCopiesFinishedTemplateWithoutChangingSourceOrActiveWorkout() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String source = uid(), e0 = uid(), e1 = uid(), s0 = uid(), s1 = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), source, "initial"))).status);
        String input = "{\"id\":\"" + source + "\",\"name\":\"Finished template\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + e0 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"preserve source note\",\"restDurationSeconds\":65,\"sets\":[{\"id\":\"" + s0 + "\",\"position\":0,\"repetitions\":12,\"weightKg\":0,\"isCompleted\":true,\"restSeconds\":42}]},{\"id\":\"" + e1 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"notes\":\"also preserve\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + s1 + "\",\"position\":0,\"repetitions\":1,\"weightKg\":99.5,\"isCompleted\":true,\"restSeconds\":null}]}]}";
        assertTrue(send(c.token, envelope(workoutMutation(uid(), source, input, 2))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), source))).body.contains("accepted"));
        String active = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), active, "keep active"))).status);
        int sourceRevision = jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(source));
        String routine = uid(), r0 = uid(), r1 = uid(), t0 = uid(), t1 = uid(), commandId = uid();
        String bindings = "[{\"sourceExerciseId\":\"" + e0 + "\",\"id\":\"" + r0 + "\",\"sets\":[{\"sourceSetId\":\"" + s0 + "\",\"id\":\"" + t0 + "\"}]},{\"sourceExerciseId\":\"" + e1 + "\",\"id\":\"" + r1 + "\",\"sets\":[{\"sourceSetId\":\"" + s1 + "\",\"id\":\"" + t1 + "\"}]}]";
        String command = "{\"mutationId\":\"" + commandId + "\",\"type\":\"workout.saveAsRoutine\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,\"payload\":{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + routine + "\",\"name\":\"Saved routine\",\"childIds\":" + bindings + "}}";
        Response response = send(c.token, envelope(command));
        assertEquals(200, response.status, response.body);
        assertTrue(response.body.contains("Saved routine"));
        assertTrue(response.body.contains("weightKg"));
        assertTrue(response.body.contains("0.0"));
        assertTrue(response.body.contains("99.5"));
        assertTrue(response.body.contains("restDurationSeconds"));
        assertTrue(response.body.contains("\"isDraft\":false"));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and name='Saved routine' and not is_draft and revision=1", Integer.class, UUID.fromString(routine)));
        assertEquals(2, jdbc.queryForObject("select count(*) from routine_exercises where routine_id=?", Integer.class, UUID.fromString(routine)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=? and position=0 and exercise_id=? and rest_duration_seconds=65", Integer.class, UUID.fromString(r0), UUID.fromString(exercise)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=? and position=1 and rest_duration_seconds=0", Integer.class, UUID.fromString(r1)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_sets where id=? and position=0 and repetitions=12 and weight_kg=0", Integer.class, UUID.fromString(t0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_sets where id=? and position=0 and repetitions=1 and weight_kg=99.5", Integer.class, UUID.fromString(t1)));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(source)));
        assertEquals(sourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(source)));
        assertEquals("preserve source note", jdbc.queryForObject("select notes from workout_exercises where id=?", String.class, UUID.fromString(e0)));
        assertEquals("active", jdbc.queryForObject("select status from workouts where id=? and deleted_at is null", String.class, UUID.fromString(active)));
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_sets where id in (?,?) and (repetitions is null or weight_kg is null)", Integer.class, UUID.fromString(t0), UUID.fromString(t1)));
        assertTrue(send(c.token, envelope(command)).body.contains("duplicate"));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(routine)));
    }

    @Test
    void saveAsRoutineRejectsInvalidSourcesBindingsNamesAndReservedIdsWithoutEffects() {
        Client a = client(), b = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String source = uid(), entry = uid(), set = uid();
        assertEquals(200, send(a.token, envelope(startMutation(uid(), source, "unfinished"))).status);
        String input = "{\"id\":\"" + source + "\",\"name\":\"x\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]}]}";
        assertTrue(send(a.token, envelope(workoutMutation(uid(), source, input, 2))).body.contains("accepted"));
        String routine = uid(), child = uid(), setChild = uid(), commandId = uid();
        String ids = "[{\"sourceExerciseId\":\"" + entry + "\",\"id\":\"" + child + "\",\"sets\":[{\"sourceSetId\":\"" + set + "\",\"id\":\"" + setChild + "\"}]}]";
        String command = "{\"mutationId\":\"" + commandId + "\",\"type\":\"workout.saveAsRoutine\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,\"payload\":{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + routine + "\",\"name\":\"Saved\",\"childIds\":" + ids + "}}";
        assertTrue(send(a.token, envelope(command)).body.contains("invalid_state"));
        assertTrue(send(b.token, envelope(command.replace(commandId, uid()))).body.contains("not_found"));
        assertTrue(send(a.token, envelope(command.replace(commandId, uid()).replace(source, uid()))).body.contains("not_found"));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(routine)));
        assertTrue(send(a.token, envelope(finishMutation(uid(), source))).body.contains("accepted"));
        String mismatch = command.replace(commandId, uid()).replace("\"sourceExerciseId\":\"" + entry + "\"", "\"sourceExerciseId\":\"" + uid() + "\"");
        assertTrue(send(a.token, envelope(mismatch)).body.contains("source_shape_changed"));
        String blank = command.replace(commandId, uid()).replace("\"name\":\"Saved\"", "\"name\":\"   \"");
        assertTrue(send(a.token, envelope(blank)).body.contains("validation_error"));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(routine)));
        String acceptedId = uid(), accepted = command.replace(commandId, acceptedId);
        Response acceptedResult = send(a.token, envelope(accepted));
        assertTrue(acceptedResult.body.contains("accepted"), acceptedResult.body);
        assertTrue(send(a.token, envelope(accepted.replace(acceptedId, uid()))).body.contains("id_collision"));
        String childCollision = accepted.replace(acceptedId, uid()).replace(routine, uid());
        assertTrue(send(a.token, envelope(childCollision)).body.contains("id_collision"));
    }

    @Test
    void startWorkoutFromSavedRoutineCopiesCanonicalTemplateAndReplacesActiveInOrder() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String routine = uid(), re0 = uid(), re1 = uid(), rs0 = uid();
        String record = "{\"id\":\"" + routine + "\",\"name\":\"Routine\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + re0 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":90,\"sets\":[{\"id\":\"" + rs0 + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]},{\"id\":\"" + re1 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"restDurationSeconds\":0,\"sets\":[]}]}";
        String create = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.upsert\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":1,\"payload\":" + record + "}";
        String save = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Saved strength\"}}";
        String oldActive = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), oldActive, "before routine"))).status);
        String workout = uid(), we0 = uid(), we1 = uid(), ws0 = uid();
        String childIds = "[{\"sourceExerciseId\":\"" + re0 + "\",\"id\":\"" + we0 + "\",\"sets\":[{\"sourceSetId\":\"" + rs0 + "\",\"id\":\"" + ws0 + "\"}]},{\"sourceExerciseId\":\"" + re1 + "\",\"id\":\"" + we1 + "\",\"sets\":[]}]";
        String start = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.startWorkout\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workout + "\",\"localGeneration\":1,\"payload\":{\"sourceRoutineId\":\"" + routine + "\",\"id\":\"" + workout + "\",\"startedAt\":\"2025-04-05T06:07:08Z\",\"childIds\":" + childIds + "}}";
        Response result = send(c.token, envelope(create, save, start));
        assertEquals(200, result.status, result.body);
        assertTrue(result.body.contains("\"status\":\"accepted\""), result.body);
        assertTrue(result.body.lastIndexOf("\"operation\":\"delete\"") < result.body.lastIndexOf("\"operation\":\"upsert\""), result.body);
        assertTrue(result.body.contains("Saved strength"));
        assertTrue(result.body.contains("\"repetitions\":null"));
        assertTrue(result.body.contains("\"weightKg\":0.0"));
        assertEquals("Saved strength", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(routine)));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=? and is_draft", Integer.class, UUID.fromString(routine)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and status='active' and deleted_at is null", Integer.class, UUID.fromString(workout)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is not null", Integer.class, UUID.fromString(oldActive)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_sets where id=? and (is_completed or rest_seconds is not null)", Integer.class, UUID.fromString(ws0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where id=? and notes='' and rest_duration_seconds=0", Integer.class, UUID.fromString(we1)));
        assertTrue(send(c.token, envelope(start)).body.contains("duplicate"));
    }

    @Test
    void routineStartRejectsMissingForeignDraftShapeAndReservedIdsWithoutMutation() {
        Client a = client(), b = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String id = uid(), entry = uid();
        String draft = "{\"id\":\"" + id + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        String create = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.upsert\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + id + "\",\"localGeneration\":1,\"payload\":" + draft + "}";
        assertTrue(send(a.token, envelope(create)).body.contains("accepted"));
        String target = uid();
        String commandId = uid();
        String childTarget = uid();
        String payload = "{\"sourceRoutineId\":\"" + id + "\",\"id\":\"" + target + "\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + entry + "\",\"id\":\"" + childTarget + "\",\"sets\":[]}]}";
        String cmd = "{\"mutationId\":\"" + commandId + "\",\"type\":\"routine.startWorkout\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + target + "\",\"localGeneration\":1,\"payload\":" + payload + "}";
        assertTrue(send(a.token, envelope(cmd)).body.contains("invalid_state"));
        assertTrue(send(b.token, envelope(cmd.replace(commandId, uid()))).body.contains("not_found"));
        assertTrue(send(a.token, envelope(cmd.replace(commandId, uid()).replace(id, uid()))).body.contains("not_found"));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(target)));
        String saved = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + id + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Saved\"}}";
        assertTrue(send(a.token, envelope(saved)).body.contains("accepted"));
        String badShape = cmd.replace(commandId, uid()).replace("\"sourceExerciseId\":\"" + entry + "\"", "\"sourceExerciseId\":\"" + uid() + "\"");
        assertTrue(send(a.token, envelope(badShape)).body.contains("source_shape_changed"));
        String reusedTarget = uid(), reusedMutation = uid();
        String reused = cmd.replace(commandId, reusedMutation).replace(target, reusedTarget);
        assertTrue(send(a.token, envelope(reused)).body.contains("accepted"));
        assertTrue(send(a.token, envelope(reused.replace(reusedMutation, uid()))).body.contains("id_collision"));
        String childCollision = reused.replace(reusedMutation, uid()).replace(reusedTarget, uid());
        assertTrue(send(a.token, envelope(childCollision)).body.contains("id_collision"));
    }

    @Test
    void performAgainCopiesFinishedWorkoutAndAtomicallyReplacesActiveWithCanonicalOrderedBatch() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String source = uid(), oldActive = uid(), e0 = uid(), e1 = uid(), s0 = uid(), s1 = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), source, "Template"))).status);
        String input = "{\"id\":\"" + source + "\",\"name\":\"Pull day\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[" +
                "{\"id\":\"" + e0 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"keep note\",\"restDurationSeconds\":75,\"sets\":[{\"id\":\"" + s0 + "\",\"position\":0,\"repetitions\":8,\"weightKg\":42.5,\"isCompleted\":true}]}," +
                "{\"id\":\"" + e1 + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"notes\":\"second\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + s1 + "\",\"position\":0,\"repetitions\":5,\"weightKg\":0,\"isCompleted\":true,\"restSeconds\":91}]}]}";
        assertEquals(200, send(c.token, envelope(workoutMutation(uid(), source, input, 2))).status);
        assertTrue(send(c.token, envelope(finishMutation(uid(), source))).body.contains("accepted"));
        assertEquals(200, send(c.token, envelope(startMutation(uid(), oldActive, "Replace me"))).status);
        String fresh = uid(), ne0 = uid(), ne1 = uid(), ns0 = uid(), ns1 = uid();
        String bindings = "[{\"sourceExerciseId\":\"" + e0 + "\",\"id\":\"" + ne0 + "\",\"sets\":[{\"sourceSetId\":\"" + s0 + "\",\"id\":\"" + ns0 + "\"}]},{\"sourceExerciseId\":\"" + e1 + "\",\"id\":\"" + ne1 + "\",\"sets\":[{\"sourceSetId\":\"" + s1 + "\",\"id\":\"" + ns1 + "\"}]}]";
        String commandMutation = uid();
        String command = "{\"mutationId\":\"" + commandMutation + "\",\"type\":\"workout.performAgain\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + fresh + "\",\"localGeneration\":1,\"payload\":{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + fresh + "\",\"startedAt\":\"2025-03-01T12:30:00Z\",\"childIds\":" + bindings + "}}";
        Response accepted = send(c.token, envelope(command));
        assertEquals(200, accepted.status, accepted.body);
        assertTrue(accepted.body.contains("\"status\":\"accepted\""), accepted.body);
        assertTrue(accepted.body.contains("keep note"));
        assertTrue(accepted.body.contains("42.5"));
        assertTrue(accepted.body.contains("restDurationSeconds"));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(source)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is not null", Integer.class, UUID.fromString(oldActive)));
        assertEquals("active", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(fresh)));
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_changes where account_id=? and cursor=(select committed_cursor from account_sync_state where account_id=?)", Integer.class, c.account, c.account));
        assertTrue(accepted.body.indexOf("\"operation\":\"delete\"") < accepted.body.indexOf("\"operation\":\"upsert\""), accepted.body);
        assertEquals(2, jdbc.queryForObject("select count(*) from workout_sets where workout_exercise_id in (?,?) and not is_completed and rest_seconds is null", Integer.class, UUID.fromString(ne0), UUID.fromString(ne1)));
        assertTrue(send(c.token, envelope(command)).body.contains("duplicate"));
        String collisionRoot = uid();
        String childCollision = command.replace(fresh, collisionRoot).replace(commandMutation, uid());
        assertTrue(send(c.token, envelope(childCollision)).body.contains("id_collision"));
        String rootCollision = command.replace(fresh, oldActive).replace(commandMutation, uid());
        assertTrue(send(c.token, envelope(rootCollision)).body.contains("id_collision"));
        String later = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), later, "later start"))).status);
        assertTrue(send(c.token, envelope(command)).body.contains("duplicate"));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(fresh)));
    }

    @Test
    void performAgainRejectsMissingIneligibleAndChangedSourceShapeWithoutEffect() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String source = uid(), entry = uid(), set = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), source, "not finished"))).status);
        String payload = "{\"id\":\"" + source + "\",\"name\":\"template\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":false}]}]}";
        assertEquals(200, send(c.token, envelope(workoutMutation(uid(), source, payload, 2))).status);
        String target = uid(), eTarget = uid(), sTarget = uid();
        String commandId = uid();
        String command = "{\"mutationId\":\"" + commandId + "\",\"type\":\"workout.performAgain\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + target + "\",\"localGeneration\":1,\"payload\":{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + target + "\",\"startedAt\":\"2025-01-02T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + entry + "\",\"id\":\"" + eTarget + "\",\"sets\":[{\"sourceSetId\":\"" + set + "\",\"id\":\"" + sTarget + "\"}]}]}}";
        assertTrue(send(c.token, envelope(command)).body.contains("invalid_state"));
        Client foreign = client();
        assertTrue(send(foreign.token, envelope(command.replace(commandId, uid()))).body.contains("not_found"));
        String missingCommand = command.replace(commandId, uid()).replace(source, uid());
        assertTrue(send(c.token, envelope(missingCommand)).body.contains("not_found"));
        String completePayload = payload.replace("\"isCompleted\":false", "\"isCompleted\":true");
        assertEquals(200, send(c.token, envelope(workoutMutation(uid(), source, completePayload, 3))).status);
        assertTrue(send(c.token, envelope(finishMutation(uid(), source))).body.contains("accepted"));
        String badBindings = command.replace(commandId, uid()).replace(entry, uid());
        assertTrue(send(c.token, envelope(badBindings)).body.contains("source_shape_changed"));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(source)));
    }

    @Test
    void workoutStartAtomicallyReplacesActiveWithOrderedDeleteAndUpsertBatch() {
        Client c = client();
        String firstId = uid(), secondId = uid();
        String first = startMutation(uid(), firstId, "First"), second = startMutation(uid(), secondId, "Second");
        Response a = send(c.token, envelope(first));
        assertEquals(200, a.status, a.body);
        Response b = send(c.token, envelope(second));
        assertEquals(200, b.status, b.body);
        assertTrue(b.body.indexOf("\"operation\":\"delete\"") < b.body.indexOf("\"operation\":\"upsert\""));
        assertTrue(b.body.contains(firstId));
        assertTrue(b.body.contains(secondId));
        var batch = parse(b.body).path("results").get(0).path("batch").path("changes");
        assertEquals(2, batch.size());
        assertEquals(firstId, batch.get(0).path("aggregateId").asText());
        assertEquals("delete", batch.get(0).path("operation").asText());
        assertEquals(2, batch.get(0).path("revision").asInt());
        assertEquals(secondId, batch.get(1).path("aggregateId").asText());
        assertEquals("upsert", batch.get(1).path("operation").asText());
        assertEquals(1, batch.get(1).path("revision").asInt());
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where owner_id=? and status='active' and deleted_at is null", Integer.class, c.account));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_changes where account_id=? and operation='delete'", Integer.class, c.account));
    }

    @Test
    void workoutUpsertReplacesActiveAggregateAndFinishCleansOnlyAfterCompletedSet() {
        Client c = client();
        String root = uid(), entry = uid(), completeSet = uid(), pendingSet = uid();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), root, "Started"))).status);
        String input = "{\"id\":\"" + root + "\",\"name\":\"Filled\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"n\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + completeSet + "\",\"position\":0,\"repetitions\":0,\"weightKg\":0.0,\"isCompleted\":true,\"restSeconds\":null},{\"id\":\"" + pendingSet + "\",\"position\":1,\"repetitions\":null,\"weightKg\":null,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        String mutation = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":2,\"payload\":" + input + "}";
        Response saved = send(c.token, envelope(mutation));
        assertEquals(200, saved.status, saved.body);
        assertTrue(saved.body.contains(completeSet));
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        Response ended = send(c.token, envelope(finish));
        assertEquals(200, ended.status, ended.body);
        assertTrue(ended.body.contains("finished"));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where workout_exercise_id=?", Integer.class, UUID.fromString(entry)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_sets where id=?", Integer.class, UUID.fromString(pendingSet)));
    }

    @Test
    void workoutAndRoutineForeignCustomReferencesAndWorkoutTargetGuardsAreNonDisclosing() {
        Client owner = client(), foreign = client();
        String foreignExercise = uid();
        String createForeign = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + foreignExercise + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + foreignExercise + "\",\"name\":\"Foreign\"}}";
        assertEquals(200, send(foreign.token, envelope(createForeign)).status);
        UUID workoutId = UUID.randomUUID();
        assertEquals(200, send(owner.token, envelope(startMutation(uid(), workoutId.toString(), "Owned"))).status);
        UUID entry = UUID.randomUUID(), set = UUID.randomUUID();
        String invalidWorkout = "{\"id\":\"" + workoutId + "\",\"name\":\"Owned\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + foreignExercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"isCompleted\":false}]}]}";
        String workoutMutation = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":" + invalidWorkout + "}";
        Response rejected = send(owner.token, envelope(workoutMutation));
        assertTrue(rejected.body.contains("invalid_reference"));
        assertFalse(rejected.body.contains(foreign.account.toString()));
        assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, workoutId));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, workoutId));
        assertFalse(rejected.body.contains("SQLState"));
        UUID routineId = UUID.randomUUID(), routineEntry = UUID.randomUUID();
        String invalidRoutine = "{\"id\":\"" + routineId + "\",\"name\":\"Draft\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + foreignExercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        String rMutation = mutation(uid(), routineId.toString(), invalidRoutine);
        Response routineRejected = send(owner.token, envelope(rMutation));
        assertTrue(routineRejected.body.contains("invalid_reference"));
        assertFalse(routineRejected.body.contains(foreign.account.toString()));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, routineId));
        UUID missingId = UUID.randomUUID();
        String missing = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + missingId + "\",\"localGeneration\":1,\"payload\":" + invalidWorkout.replace(workoutId.toString(), missingId.toString()) + "}";
        assertTrue(send(owner.token, envelope(missing)).body.contains("not_found"));
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        assertTrue(send(owner.token, envelope(finish)).body.contains("validation_error"));
        String complete = invalidWorkout.replace(foreignExercise, jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString()).replace("\"isCompleted\":false", "\"repetitions\":1,\"weightKg\":0.0,\"isCompleted\":true");
        String goodUpsert = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":" + complete + "}";
        assertEquals(200, send(owner.token, envelope(goodUpsert)).status);
        String finishEligible = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-03T00:00:00Z\"}}";
        Response finishResult = send(owner.token, envelope(finishEligible));
        assertTrue(finishResult.body.contains("accepted"));
        assertTrue(send(owner.token, envelope(finishEligible)).body.contains("duplicate"));
        String afterFinished = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":3,\"payload\":" + complete + "}";
        assertTrue(send(owner.token, envelope(afterFinished)).body.contains("invalid_state"));
        assertEquals(3, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, workoutId));
    }

    @Test
    void finishPrunesUnfinishedRowsAndRenumbersMultipleSurvivingEntriesAndSets() {
        Client c = client();
        String root = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), root, "Cleanup"))).status);
        String cat = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String e0 = uid(), e1 = uid(), e2 = uid(), s0 = uid(), u0 = uid(), s1 = uid(), s2 = uid(), u1 = uid(), u2 = uid();
        String input = "{\"id\":\"" + root + "\",\"name\":\"Cleanup\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":["
                + "{\"id\":\"" + e0 + "\",\"exerciseId\":\"" + cat + "\",\"position\":0,\"notes\":\"a\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + s0 + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1.0,\"isCompleted\":true},{\"id\":\"" + u0 + "\",\"position\":1,\"isCompleted\":false},{\"id\":\"" + s1 + "\",\"position\":2,\"repetitions\":2,\"weightKg\":2.0,\"isCompleted\":true}]},"
                + "{\"id\":\"" + e1 + "\",\"exerciseId\":\"" + cat + "\",\"position\":1,\"notes\":\"b\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + s2 + "\",\"position\":0,\"repetitions\":3,\"weightKg\":3.0,\"isCompleted\":true},{\"id\":\"" + u1 + "\",\"position\":1,\"isCompleted\":false}]},"
                + "{\"id\":\"" + e2 + "\",\"exerciseId\":\"" + cat + "\",\"position\":2,\"notes\":\"c\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + u2 + "\",\"position\":0,\"isCompleted\":false}]}]}";
        String upsert = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":2,\"payload\":" + input + "}";
        assertEquals(200, send(c.token, envelope(upsert)).status);
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        Response done = send(c.token, envelope(finish));
        assertEquals(200, done.status, done.body);
        assertTrue(done.body.contains(e0));
        assertTrue(done.body.contains(e1));
        assertFalse(done.body.contains(e2));
        assertEquals(0, jdbc.queryForObject("select position from workout_exercises where id=?", Integer.class, UUID.fromString(e0)));
        assertEquals(1, jdbc.queryForObject("select position from workout_exercises where id=?", Integer.class, UUID.fromString(e1)));
        assertEquals(0, jdbc.queryForObject("select position from workout_sets where id=?", Integer.class, UUID.fromString(s0)));
        assertEquals(1, jdbc.queryForObject("select position from workout_sets where id=?", Integer.class, UUID.fromString(s1)));
        assertEquals(0, jdbc.queryForObject("select position from workout_sets where id=?", Integer.class, UUID.fromString(s2)));
        for (String removed : List.of(u0, u1, u2))
            assertEquals(0, jdbc.queryForObject("select count(*) from workout_sets where id=?", Integer.class, UUID.fromString(removed)));
        Response replay = send(c.token, envelope(finish));
        assertTrue(replay.body.contains("duplicate"));
        String anotherFinish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":4,\"payload\":{\"finishedAt\":\"2025-01-03T00:00:00Z\"}}";
        assertTrue(send(c.token, envelope(anotherFinish)).body.contains("invalid_state"));
    }

    @Test
    void supportedMixedPushContinuesAfterDomainRejection() {
        Client c = client();
        String badId = uid(), missingMuscle = uid(), workoutId = uid();
        String bad = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + badId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + badId + "\",\"name\":\"bad\",\"primaryMuscleGroupId\":\"" + missingMuscle + "\"}}";
        Response result = send(c.token, envelope(bad, startMutation(uid(), workoutId, "after rejection")));
        assertEquals(200, result.status, result.body);
        var outcomes = parse(result.body).path("results");
        assertEquals("rejected", outcomes.get(0).path("status").asText());
        assertEquals("invalid_reference", outcomes.get(0).path("errorCode").asText());
        assertEquals("accepted", outcomes.get(1).path("status").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is null", Integer.class, UUID.fromString(workoutId)));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
    }

    @Test
    void newKindsRejectInvalidDomainWritesWithoutChangingRootsOrRevisions() {
        Client c = client();
        String customId = uid(), unknownMuscle = uid();
        String badCustom = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + customId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + customId + "\",\"name\":\"Bad\",\"primaryMuscleGroupId\":\"" + unknownMuscle + "\"}}";
        assertTrue(send(c.token, envelope(badCustom)).body.contains("invalid_reference"));
        assertEquals(0, jdbc.queryForObject("select count(*) from exercises where id=?", Integer.class, UUID.fromString(customId)));

        String routineId = uid();
        String createDraft = mutation(uid(), routineId, "{\"id\":\"" + routineId + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}");
        assertEquals(200, send(c.token, envelope(createDraft)).status);
        String emptySave = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routineId + "\",\"localGeneration\":2,\"payload\":{\"name\":\"  \"}}";
        assertTrue(send(c.token, envelope(emptySave)).body.contains("validation_error"));
        assertEquals(1, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(routineId)));

        String workoutId = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), workoutId, "Active"))).status);
        String collision = startMutation(uid(), workoutId, "Collision");
        assertTrue(send(c.token, envelope(collision)).body.contains("id_collision"));
        assertEquals("Active", jdbc.queryForObject("select name from workouts where id=? and deleted_at is null", String.class, UUID.fromString(workoutId)));
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        assertTrue(send(c.token, envelope(finish)).body.contains("validation_error"));
        assertEquals("active", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(workoutId)));
        assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(workoutId)));
    }

    @Test
    void concurrentWorkoutStartsSerializeAndLeaveOneActiveRoot() throws Exception {
        Client c = client();
        String w1 = uid(), w2 = uid();
        String body1 = envelope(startMutation(uid(), w1, "A")), body2 = envelope(startMutation(uid(), w2, "B"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Response> a = pool.submit(() -> {
                gate.await();
                return send(c.token, body1);
            });
            Future<Response> b = pool.submit(() -> {
                gate.await();
                return send(c.token, body2);
            });
            gate.countDown();
            assertEquals(200, a.get().status);
            assertEquals(200, b.get().status);
            assertEquals(1, jdbc.queryForObject("select count(*) from workouts where owner_id=? and status='active' and deleted_at is null", Integer.class, c.account));
            assertEquals(2, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void orderedCustomStartWorkoutUpsertFinishPushUsesEarlierCommittedDependencies() {
        Client c = client();
        String customId = uid(), workoutId = uid(), entry = uid(), set = uid();
        String custom = "{\"mutationId\":\"" + uid() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + customId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + customId + "\",\"name\":\"Dependency\"}}";
        String start = startMutation(uid(), workoutId, "Ordered");
        String payload = "{\"id\":\"" + workoutId + "\",\"name\":\"Ordered\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + customId + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":1,\"weightKg\":0.0,\"isCompleted\":true}]}]}";
        String upsert = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":2,\"payload\":" + payload + "}";
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        Response response = send(c.token, envelope(custom, start, upsert, finish));
        assertEquals(200, response.status, response.body);
        assertEquals(4, response.body.split("\"status\":\"accepted\"", -1).length - 1);
        assertTrue(response.body.contains(customId));
        assertTrue(response.body.contains(entry));
        assertTrue(response.body.contains(set));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(workoutId)));
        assertEquals(4, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
    }

    @Test
    void customExerciseImportsPreserveOptionalMetadataNullEmptyAndZeroDistinctions() {
        Client c = client();
        UUID muscle = jdbc.queryForObject("select id from muscle_groups order by id limit 1", UUID.class);
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        String richId = uid(), omittedId = uid(), nullableId = uid();
        String rich = importMutation(uid(), "customExercise", richId,
                "{\"id\":\"" + richId + "\",\"name\":\"Rich imported\",\"primaryMuscleGroupId\":\"" + muscle + "\",\"secondaryMuscleGroupIds\":[],\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":0,\"category\":\"Strength\",\"difficultyLevel\":\"Advanced\",\"forceType\":\"Push\",\"mechanics\":\"Compound\",\"instructions\":[]}");
        String omitted = importMutation(uid(), "customExercise", omittedId,
                "{\"id\":\"" + omittedId + "\",\"name\":\"Omitted optionals\"}");
        String explicitNulls = importMutation(uid(), "customExercise", nullableId,
                "{\"id\":\"" + nullableId + "\",\"name\":\"Explicit null optionals\",\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":null,\"instructions\":null}");
        var outcomes = parse(send(c.token, envelope(rich, omitted, explicitNulls)).body).path("results");
        assertEquals(3, outcomes.size());
        assertEquals("accepted", outcomes.get(0).path("status").asText());
        assertEquals("accepted", outcomes.get(1).path("status").asText());
        assertEquals("accepted", outcomes.get(2).path("status").asText());

        var richRecord = outcomes.get(0).path("batch").path("changes").get(0).path("record");
        assertEquals(richId, richRecord.path("id").asText());
        assertEquals("Rich imported", richRecord.path("name").asText());
        assertEquals("reps", richRecord.path("type").asText());
        assertEquals("Strength", richRecord.path("category").asText());
        assertEquals("Advanced", richRecord.path("difficultyLevel").asText());
        assertEquals("Push", richRecord.path("forceType").asText());
        assertEquals("Compound", richRecord.path("mechanics").asText());
        assertTrue(richRecord.has("instructions") && richRecord.path("instructions").isArray());
        assertEquals(0, richRecord.path("instructions").size());
        assertEquals(1, richRecord.path("muscleGroups").size());
        assertEquals(muscle.toString(), richRecord.path("muscleGroups").get(0).path("id").asText());
        assertEquals("primary", richRecord.path("muscleGroups").get(0).path("type").asText());
        assertEquals(1, richRecord.path("equipment").size());
        assertEquals(equipment.toString(), richRecord.path("equipment").get(0).path("id").asText());
        assertEquals(0, richRecord.path("equipment").get(0).path("numItems").asInt());
        assertEquals(1, richRecord.path("revision").asLong());

        var omittedRecord = outcomes.get(1).path("batch").path("changes").get(0).path("record");
        assertEquals(omittedId, omittedRecord.path("id").asText());
        assertEquals("Omitted optionals", omittedRecord.path("name").asText());
        for (String nullable : List.of("category", "difficultyLevel", "forceType", "mechanics", "instructions"))
            assertTrue(omittedRecord.path(nullable).isNull(), "omitted " + nullable + " must have null canonical value");
        assertTrue(omittedRecord.path("muscleGroups").isArray());
        assertEquals(0, omittedRecord.path("muscleGroups").size());
        assertTrue(omittedRecord.path("equipment").isArray());
        assertEquals(0, omittedRecord.path("equipment").size());
        assertNull(jdbc.queryForObject("select category from exercises where id=?", String.class, UUID.fromString(omittedId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from exercise_equipment where exercise_id=?", Integer.class, UUID.fromString(omittedId)));
        var nullableRecord = outcomes.get(2).path("batch").path("changes").get(0).path("record");
        assertEquals(nullableId, nullableRecord.path("id").asText());
        assertTrue(nullableRecord.path("instructions").isNull());
        assertEquals(1, nullableRecord.path("equipment").size());
        assertTrue(nullableRecord.path("equipment").get(0).path("numItems").isNull());
        assertEquals(equipment.toString(), nullableRecord.path("equipment").get(0).path("id").asText());
        assertNull(jdbc.queryForObject("select num_items from exercise_equipment where exercise_id=?", Integer.class, UUID.fromString(nullableId)));
    }

    @Test
    void workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String workout = uid(), entry = uid(), set = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), workout, "reservation source"))).body.contains("accepted"));
        String original = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"before\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"isCompleted\":false}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), workout, original))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), workout, "[]"))).body.contains("accepted"));
        var restore = parse(send(c.token, envelope(workoutUpsert(uid(), workout, original))).body).path("results").get(0);
        assertEquals("accepted", restore.path("status").asText());
        var restoredRecord = restore.path("batch").path("changes").get(0).path("record");
        assertEquals(entry, restoredRecord.path("exercises").get(0).path("id").asText());
        assertEquals(set, restoredRecord.path("exercises").get(0).path("sets").get(0).path("id").asText());

        assertTrue(send(c.token, envelope(workoutUpsert(uid(), workout, "[]"))).body.contains("accepted"));
        String otherParent = uid();
        String reparented = "[{\"id\":\"" + otherParent + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"isCompleted\":false}]}]";
        long revisionBefore = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(workout));
        long cursorBefore = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        var collision = parse(send(c.token, envelope(workoutUpsert(uid(), workout, reparented))).body).path("results").get(0);
        assertEquals("id_collision", collision.path("errorCode").asText());
        assertEquals(revisionBefore, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(workout)));
        assertEquals(cursorBefore, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where id=?", Integer.class, UUID.fromString(otherParent)));

        // Workout and routine child-ID reservation families are independent.
        String routine = uid();
        String routineWithSameChildIds = "{\"id\":\"" + routine + "\",\"name\":\"Independent family\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + set + "\",\"position\":0}]}]}";
        var independent = parse(send(c.token, envelope(mutation(uid(), routine, routineWithSameChildIds))).body).path("results").get(0);
        assertEquals("accepted", independent.path("status").asText());
        assertEquals(entry, independent.path("batch").path("changes").get(0).path("record").path("exercises").get(0).path("id").asText());
        assertEquals(set, independent.path("batch").path("changes").get(0).path("record").path("exercises").get(0).path("sets").get(0).path("id").asText());
    }

    @Test
    void importAndCopyReplayKeepOriginalExerciseLabelsAcrossRenameAndLaterAutosave() {
        Client c = client();
        String custom = uid();
        String originalCustomMutation = customMutation(uid(), custom, "Original label", "\"secondaryMuscleGroupIds\":[]");
        assertEquals("accepted", parse(send(c.token, envelope(originalCustomMutation)).body).path("results").get(0).path("status").asText());
        String source = uid(), sourceEntry = uid(), sourceSet = uid();
        String sourceRecord = "{\"id\":\"" + source + "\",\"name\":\"History\",\"status\":\"finished\",\"startedAt\":\"2025-08-01T00:00:00Z\",\"finishedAt\":\"2025-08-01T01:00:00Z\",\"exercises\":[{\"id\":\"" + sourceEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"historical note\",\"restDurationSeconds\":62,\"sets\":[{\"id\":\"" + sourceSet + "\",\"position\":0,\"repetitions\":6,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":8}]}]}";
        String importRequest = importMutation(uid(), "workout", source, sourceRecord);
        var imported = parse(send(c.token, envelope(importRequest)).body).path("results").get(0);
        assertEquals("accepted", imported.path("status").asText());
        var importBatch = imported.path("batch");
        var importedRecord = importBatch.path("changes").get(0).path("record");
        assertEquals("Original label", importedRecord.path("exercises").get(0).path("exerciseName").asText());

        String target = uid(), targetEntry = uid(), targetSet = uid();
        String perform = copyMutation(uid(), "workout.performAgain", "workout", target,
                "{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + target + "\",\"startedAt\":\"2025-08-02T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + sourceEntry + "\",\"id\":\"" + targetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + sourceSet + "\",\"id\":\"" + targetSet + "\"}]}]}");
        var copied = parse(send(c.token, envelope(perform)).body).path("results").get(0);
        assertEquals("accepted", copied.path("status").asText());
        var copyBatch = copied.path("batch");
        assertEquals("Original label", copyBatch.path("changes").get(0).path("record").path("exercises").get(0).path("exerciseName").asText());
        long sourceRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source));
        long copyRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(target));

        String rename = customMutation(uid(), custom, "Renamed label", "\"secondaryMuscleGroupIds\":[]");
        var renameResult = parse(send(c.token, envelope(rename)).body).path("results").get(0);
        assertEquals("accepted", renameResult.path("status").asText());
        assertEquals(1, renameResult.path("batch").path("changes").size(), "rename publishes only the custom exercise");
        assertEquals("customExercise", renameResult.path("batch").path("changes").get(0).path("aggregateType").asText());
        assertEquals(sourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source)));
        assertEquals(copyRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(target)));

        String laterEdit = "{\"id\":\"" + target + "\",\"name\":\"History\",\"status\":\"active\",\"startedAt\":\"2025-08-02T00:00:00Z\",\"exercises\":[{\"id\":\"" + targetEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"later edit\",\"restDurationSeconds\":62,\"sets\":[{\"id\":\"" + targetSet + "\",\"position\":0,\"repetitions\":6,\"weightKg\":10,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        assertEquals("accepted", parse(send(c.token, envelope(workoutMutation(uid(), target, laterEdit, 2))).body).path("results").get(0).path("status").asText());
        long winningCopyRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(target));

        var importReplay = parse(send(c.token, envelope(importRequest)).body).path("results").get(0);
        var copyReplay = parse(send(c.token, envelope(perform)).body).path("results").get(0);
        assertEquals("duplicate", importReplay.path("status").asText());
        assertEquals("duplicate", copyReplay.path("status").asText());
        assertEquals(importBatch, importReplay.path("batch"));
        assertEquals(copyBatch, copyReplay.path("batch"));
        assertEquals("Original label", importReplay.path("batch").path("changes").get(0).path("record").path("exercises").get(0).path("exerciseName").asText());
        assertEquals("Original label", copyReplay.path("batch").path("changes").get(0).path("record").path("exercises").get(0).path("exerciseName").asText());
        assertEquals("Renamed label", canonical.readWorkout(c.account, UUID.fromString(source)).exercises().getFirst().exerciseName());
        var freshCopy = canonical.readWorkout(c.account, UUID.fromString(target));
        assertEquals("Renamed label", freshCopy.exercises().getFirst().exerciseName());
        assertEquals("later edit", freshCopy.exercises().getFirst().value().notes());
        assertEquals(sourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source)));
        assertEquals(winningCopyRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(target)));
    }

    @Test
    void workoutRootIdCannotClaimAnIdReservedForAnExistingWorkoutChild() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String existingRoot = uid(), childId = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), existingRoot, "reservation owner"))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), existingRoot,
                "[{\"id\":\"" + childId + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]"))).body.contains("accepted"));
        long revisionBefore = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(existingRoot));
        long cursorBefore = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        int batchesBefore = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        String attemptedRootId = childId;
        String collisionRequest = startMutation(uid(), attemptedRootId, "illegal role reuse");
        Response attempted = send(c.token, envelope(collisionRequest));
        Response retry = send(c.token, envelope(collisionRequest));
        var result = attempted.status == 200 ? parse(attempted.body).path("results").get(0) : null;
        var replay = retry.status == 200 ? parse(retry.body).path("results").get(0) : null;
        assertAll("root-vs-child UUID collision is a durable non-disclosing domain id_collision",
                () -> assertEquals(200, attempted.status, attempted.body),
                () -> assertNotNull(result, attempted.body),
                () -> assertEquals("rejected", result == null ? null : result.path("status").asText()),
                () -> assertEquals("id_collision", result == null ? null : result.path("errorCode").asText()),
                () -> assertTrue(result == null || result.path("batch").isNull()),
                () -> assertEquals(200, retry.status, retry.body),
                () -> assertNotNull(replay, retry.body),
                () -> assertEquals("duplicate", replay == null ? null : replay.path("status").asText()),
                () -> assertEquals("id_collision", replay == null ? null : replay.path("errorCode").asText()),
                () -> assertTrue(replay == null || replay.path("batch").isNull()),
                () -> assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(attemptedRootId))),
                () -> assertEquals(1, jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' and child_id=? and owner_id=? and root_id=? and role='entry'", Integer.class, UUID.fromString(childId), c.account, UUID.fromString(existingRoot))),
                () -> assertEquals(0, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' and aggregate_id=?", Integer.class, UUID.fromString(attemptedRootId))),
                () -> assertEquals(revisionBefore, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(existingRoot))),
                () -> assertEquals(existingRoot, activeWorkout(c)),
                () -> assertEquals(cursorBefore, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account)),
                () -> assertEquals(batchesBefore, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account)),
                () -> assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=? and mutation_id=?", Integer.class, c.account, UUID.fromString(parse(collisionRequest).path("mutationId").asText()))));
    }

    @Test
    void cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String source = uid(), firstEntry = uid(), secondEntry = uid(), firstSet = uid(), secondSet = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), source, "binding source"))).body.contains("accepted"));
        String exercises = "[{\"id\":\"" + firstEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"one\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + firstSet + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]},{\"id\":\"" + secondEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"notes\":\"two\",\"restDurationSeconds\":40,\"sets\":[{\"id\":\"" + secondSet + "\",\"position\":0,\"repetitions\":2,\"weightKg\":2,\"isCompleted\":true}]}]";
        assertTrue(send(c.token, envelope(workoutMutation(uid(), source, "{\"id\":\"" + source + "\",\"name\":\"binding source\",\"status\":\"active\",\"startedAt\":\"2025-09-01T00:00:00Z\",\"exercises\":" + exercises + "}", 2))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), source))).body.contains("accepted"));
        long sourceRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source));

        String missing = "[" + cloneBinding(firstEntry, uid(), firstSet, uid()) + "]";
        String complete = "[" + cloneBinding(firstEntry, uid(), firstSet, uid()) + "," + cloneBinding(secondEntry, uid(), secondSet, uid()) + "]";
        String extra = complete.substring(0, complete.length() - 1) + ",{\"sourceExerciseId\":\"" + uid() + "\",\"id\":\"" + uid() + "\",\"sets\":[]}]";
        String reordered = "[" + cloneBinding(secondEntry, uid(), secondSet, uid()) + "," + cloneBinding(firstEntry, uid(), firstSet, uid()) + "]";
        List<String> bindings = List.of(missing, extra, reordered);
        long cursorBefore = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        int batchesBefore = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        for (String binding : bindings) {
            String target = uid();
            String request = copyMutation(uid(), "workout.performAgain", "workout", target,
                    "{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + target + "\",\"startedAt\":\"2025-09-02T00:00:00Z\",\"childIds\":" + binding + "}");
            var result = parse(send(c.token, envelope(request)).body).path("results").get(0);
            assertEquals("rejected", result.path("status").asText());
            assertEquals("source_shape_changed", result.path("errorCode").asText());
            assertTrue(result.path("batch").isNull());
            assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(target)));
            assertEquals(0, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' and aggregate_id=?", Integer.class, UUID.fromString(target)));
            assertEquals(sourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source)));
            assertEquals(cursorBefore, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account));
            assertEquals(batchesBefore, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            var replay = parse(send(c.token, envelope(request)).body).path("results").get(0);
            assertEquals("duplicate", replay.path("status").asText());
            assertEquals("source_shape_changed", replay.path("errorCode").asText());
        }

        String priorRoot = uid(), removedEntry = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), priorRoot, "removed target identity"))).body.contains("accepted"));
        String priorInput = "[{\"id\":\"" + removedEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), priorRoot, priorInput))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), priorRoot, "[]"))).body.contains("accepted"));
        String freshTarget = uid(), freshSet = uid(), freshSecondEntry = uid(), freshSecondSet = uid();
        String reuseRemoved = copyMutation(uid(), "workout.performAgain", "workout", freshTarget,
                "{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + freshTarget + "\",\"startedAt\":\"2025-09-03T00:00:00Z\",\"childIds\":[" + cloneBinding(firstEntry, removedEntry, firstSet, freshSet) + "," + cloneBinding(secondEntry, freshSecondEntry, secondSet, freshSecondSet) + "]}");
        long priorRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(priorRoot));
        long sourceRevisionBeforeCollision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source));
        long cursorBeforeCollision = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        int batchesBeforeCollision = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        var collision = parse(send(c.token, envelope(reuseRemoved)).body).path("results").get(0);
        assertEquals("rejected", collision.path("status").asText());
        assertEquals("id_collision", collision.path("errorCode").asText());
        assertTrue(collision.path("batch").isNull());
        var collisionReplay = parse(send(c.token, envelope(reuseRemoved)).body).path("results").get(0);
        assertEquals("duplicate", collisionReplay.path("status").asText());
        assertEquals("id_collision", collisionReplay.path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(freshTarget)));
        assertEquals(0, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' and aggregate_id=?", Integer.class, UUID.fromString(freshTarget)));
        assertEquals(0, jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' and child_id=?", Integer.class, UUID.fromString(freshSecondEntry)));
        assertEquals(1, jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' and child_id=? and root_id=? and role='entry'", Integer.class, UUID.fromString(removedEntry), UUID.fromString(priorRoot)));
        assertEquals(priorRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(priorRoot)));
        assertEquals(sourceRevisionBeforeCollision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(source)));
        assertEquals(priorRoot, activeWorkout(c));
        assertEquals(cursorBeforeCollision, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account));
        assertEquals(batchesBeforeCollision, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
    }

    private String cloneBinding(String sourceEntry, String targetEntry, String sourceSet, String targetSet) {
        return "{\"sourceExerciseId\":\"" + sourceEntry + "\",\"id\":\"" + targetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + sourceSet + "\",\"id\":\"" + targetSet + "\"}]}";
    }

    @Test
    void integratedImportCopyAutosaveAndDependencyDeleteLifecycleKeepsCanonicalOrderAndSnapshots() {
        Client c = client();
        UUID muscle = jdbc.queryForObject("select id from muscle_groups order by id limit 1", UUID.class);
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        String oldActive = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), oldActive, "Pre-import active"))).body.contains("accepted"));

        String custom = uid(), finished = uid(), routine = uid(), importedActive = uid();
        String finishedEntry = uid(), finishedSet = uid(), routineEntry = uid(), routineSet = uid();
        String customRecord = "{\"id\":\"" + custom + "\",\"name\":\"Imported movement\",\"primaryMuscleGroupId\":\"" + muscle + "\",\"secondaryMuscleGroupIds\":[],\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":null,\"category\":null,\"difficultyLevel\":\"Intermediate\",\"forceType\":null,\"mechanics\":\"Compound\",\"instructions\":null}";
        String finishedRecord = "{\"id\":\"" + finished + "\",\"name\":\"Imported history\",\"status\":\"finished\",\"startedAt\":\"2025-07-01T00:00:00Z\",\"finishedAt\":\"2025-07-01T01:00:00Z\",\"exercises\":[{\"id\":\"" + finishedEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"source notes\",\"restDurationSeconds\":75,\"sets\":[{\"id\":\"" + finishedSet + "\",\"position\":0,\"repetitions\":8,\"weightKg\":0,\"isCompleted\":true,\"restSeconds\":19}]}]}";
        String routineRecord = "{\"id\":\"" + routine + "\",\"name\":\"Imported template\",\"isDraft\":false,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + routineSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        String importCustom = importMutation(uid(), "customExercise", custom, customRecord);
        String importFinished = importMutation(uid(), "workout", finished, finishedRecord);
        String importRoutine = importMutation(uid(), "routine", routine, routineRecord);
        String importActive = importMutation(uid(), "workout", importedActive,
                "{\"id\":\"" + importedActive + "\",\"name\":\"Current imported\",\"status\":\"active\",\"startedAt\":\"2025-07-02T00:00:00Z\",\"exercises\":[]}");
        var importResults = parse(send(c.token, envelope(importCustom, importFinished, importRoutine, importActive)).body).path("results");
        assertEquals(4, importResults.size());
        for (int i = 0; i < importResults.size(); i++) {
            assertEquals(parse(List.of(importCustom, importFinished, importRoutine, importActive).get(i)).path("mutationId").asText(), importResults.get(i).path("mutationId").asText());
            assertEquals("accepted", importResults.get(i).path("status").asText());
        }
        var replacement = importResults.get(3).path("batch").path("changes");
        assertEquals(2, replacement.size());
        assertEquals(oldActive, replacement.get(0).path("aggregateId").asText());
        assertEquals("delete", replacement.get(0).path("operation").asText());
        assertEquals(importedActive, replacement.get(1).path("aggregateId").asText());
        assertEquals("upsert", replacement.get(1).path("operation").asText());

        String performTarget = uid(), performEntry = uid(), performSet = uid();
        String perform = copyMutation(uid(), "workout.performAgain", "workout", performTarget,
                "{\"sourceWorkoutId\":\"" + finished + "\",\"id\":\"" + performTarget + "\",\"startedAt\":\"2025-07-03T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + finishedEntry + "\",\"id\":\"" + performEntry + "\",\"sets\":[{\"sourceSetId\":\"" + finishedSet + "\",\"id\":\"" + performSet + "\"}]}]}");
        String routineTarget = uid(), routineTargetEntry = uid(), routineTargetSet = uid();
        String startRoutine = copyMutation(uid(), "routine.startWorkout", "workout", routineTarget,
                "{\"sourceRoutineId\":\"" + routine + "\",\"id\":\"" + routineTarget + "\",\"startedAt\":\"2025-07-04T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + routineEntry + "\",\"id\":\"" + routineTargetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + routineSet + "\",\"id\":\"" + routineTargetSet + "\"}]}]}");
        String savedRoutine = uid(), savedEntry = uid(), savedSet = uid();
        String saveAs = copyMutation(uid(), "workout.saveAsRoutine", "routine", savedRoutine,
                "{\"sourceWorkoutId\":\"" + finished + "\",\"id\":\"" + savedRoutine + "\",\"name\":\"Copied history\",\"childIds\":[{\"sourceExerciseId\":\"" + finishedEntry + "\",\"id\":\"" + savedEntry + "\",\"sets\":[{\"sourceSetId\":\"" + finishedSet + "\",\"id\":\"" + savedSet + "\"}]}]}");
        var copies = parse(send(c.token, envelope(perform, startRoutine, saveAs)).body).path("results");
        assertEquals(3, copies.size());
        assertEquals(List.of(perform, startRoutine, saveAs).stream().map(raw -> parse(raw).path("mutationId").asText()).toList(),
                List.of(copies.get(0).path("mutationId").asText(), copies.get(1).path("mutationId").asText(), copies.get(2).path("mutationId").asText()));
        assertEquals(List.of("accepted", "accepted", "accepted"), List.of(copies.get(0).path("status").asText(), copies.get(1).path("status").asText(), copies.get(2).path("status").asText()));
        var performChanges = copies.get(0).path("batch").path("changes");
        assertEquals(2, performChanges.size());
        assertEquals(importedActive, performChanges.get(0).path("aggregateId").asText());
        assertEquals("delete", performChanges.get(0).path("operation").asText());
        assertEquals(performTarget, performChanges.get(1).path("aggregateId").asText());
        assertEquals("upsert", performChanges.get(1).path("operation").asText());
        var performed = performChanges.get(1).path("record");
        assertEquals("Imported history", performed.path("name").asText());
        assertEquals("active", performed.path("status").asText());
        assertEquals(performEntry, performed.path("exercises").get(0).path("id").asText());
        assertEquals("source notes", performed.path("exercises").get(0).path("notes").asText());
        assertEquals(75, performed.path("exercises").get(0).path("restDurationSeconds").asInt());
        assertEquals(performSet, performed.path("exercises").get(0).path("sets").get(0).path("id").asText());
        assertEquals(8, performed.path("exercises").get(0).path("sets").get(0).path("repetitions").asInt());
        assertEquals(0.0, performed.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        assertFalse(performed.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertTrue(performed.path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());

        var startChanges = copies.get(1).path("batch").path("changes");
        assertEquals(2, startChanges.size());
        assertEquals(performTarget, startChanges.get(0).path("aggregateId").asText());
        assertEquals("delete", startChanges.get(0).path("operation").asText());
        assertEquals(routineTarget, startChanges.get(1).path("aggregateId").asText());
        var started = startChanges.get(1).path("record");
        assertEquals("Imported template", started.path("name").asText());
        assertEquals(routineTargetEntry, started.path("exercises").get(0).path("id").asText());
        assertEquals("", started.path("exercises").get(0).path("notes").asText());
        assertEquals(0, started.path("exercises").get(0).path("restDurationSeconds").asInt());
        assertEquals(routineTargetSet, started.path("exercises").get(0).path("sets").get(0).path("id").asText());
        assertTrue(started.path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertEquals(0.0, started.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        assertFalse(started.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertTrue(started.path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());

        var saved = copies.get(2).path("batch").path("changes").get(0).path("record");
        assertEquals(savedRoutine, saved.path("id").asText());
        assertEquals("Copied history", saved.path("name").asText());
        assertFalse(saved.path("isDraft").asBoolean());
        assertEquals(savedEntry, saved.path("exercises").get(0).path("id").asText());
        assertEquals(savedSet, saved.path("exercises").get(0).path("sets").get(0).path("id").asText());
        assertTrue(saved.path("exercises").get(0).path("sets").get(0).path("repetitions").asInt() == 8);
        assertEquals(0.0, saved.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        assertFalse(saved.path("exercises").get(0).path("sets").get(0).has("isCompleted"));
        assertFalse(saved.path("exercises").get(0).path("sets").get(0).has("restSeconds"));
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_changes where account_id=? and cursor=(select committed_cursor from account_sync_state where account_id=?)", Integer.class, c.account, c.account));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(finished)));
        assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(finished)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and is_completed and rest_seconds=19", Integer.class, UUID.fromString(finishedSet)));
        assertEquals("Imported movement", canonical.readCustomExercise(c.account, UUID.fromString(custom)).name());

        String updatedRecord = "{\"id\":\"" + routineTarget + "\",\"name\":\"Edited clone\",\"status\":\"active\",\"startedAt\":\"2025-07-04T00:00:00Z\",\"exercises\":[{\"id\":\"" + routineTargetEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"edited after copy\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + routineTargetSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        assertTrue(send(c.token, envelope(workoutMutation(uid(), routineTarget, updatedRecord, 2))).body.contains("accepted"));
        assertEquals("edited after copy", canonical.readWorkout(c.account, UUID.fromString(routineTarget)).exercises().getFirst().value().notes());
        var blocked = parse(send(c.token, envelope(deleteMutation(uid(), "customExercise", custom))).body).path("results").get(0);
        assertEquals("exercise_in_use", blocked.path("errorCode").asText());

        var deletions = parse(send(c.token, envelope(
                deleteMutation(uid(), "workout", routineTarget),
                deleteMutation(uid(), "routine", savedRoutine),
                deleteMutation(uid(), "routine", routine),
                deleteMutation(uid(), "workout", finished),
                deleteMutation(uid(), "customExercise", custom))).body).path("results");
        assertEquals(5, deletions.size());
        for (var outcome : deletions) assertEquals("accepted", outcome.path("status").asText());
        assertEquals("delete", deletions.get(4).path("batch").path("changes").get(0).path("operation").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from exercises where id=? and deleted_at is null", Integer.class, UUID.fromString(custom)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where owner_id=? and status='active' and deleted_at is null", Integer.class, c.account));
    }

    @Test
    void allCopyKindsReplayAcceptedAndRejectedOutcomesAcrossRealProcessRestartAfterStateChanges() throws Exception {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String finished = finishedWorkout(c, exercise, "Restart source");
        String finishedEntry = workoutEntry(c, finished), finishedSet = workoutSet(c, finished);

        String savedRoutine = uid(), sourceRoutineEntry = uid(), sourceRoutineSet = uid();
        String savedInput = "{\"id\":\"" + savedRoutine + "\",\"name\":\"Routine template\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + sourceRoutineEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":45,\"sets\":[{\"id\":\"" + sourceRoutineSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        assertTrue(send(c.token, envelope(mutation(uid(), savedRoutine, savedInput))).body.contains("accepted"));
        assertTrue(send(c.token, envelope("{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + savedRoutine + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Routine template\"}}")).body.contains("accepted"));

        String performTarget = uid(), performEntry = uid(), performSet = uid();
        String perform = copyMutation(uid(), "workout.performAgain", "workout", performTarget,
                "{\"sourceWorkoutId\":\"" + finished + "\",\"id\":\"" + performTarget + "\",\"startedAt\":\"2025-06-01T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + finishedEntry + "\",\"id\":\"" + performEntry + "\",\"sets\":[{\"sourceSetId\":\"" + finishedSet + "\",\"id\":\"" + performSet + "\"}]}]}");
        String routineTarget = uid(), routineTargetEntry = uid(), routineTargetSet = uid();
        String startRoutine = copyMutation(uid(), "routine.startWorkout", "workout", routineTarget,
                "{\"sourceRoutineId\":\"" + savedRoutine + "\",\"id\":\"" + routineTarget + "\",\"startedAt\":\"2025-06-02T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + sourceRoutineEntry + "\",\"id\":\"" + routineTargetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + sourceRoutineSet + "\",\"id\":\"" + routineTargetSet + "\"}]}]}");
        String saveTarget = uid(), saveEntry = uid(), saveSet = uid();
        String saveAsRoutine = copyMutation(uid(), "workout.saveAsRoutine", "routine", saveTarget,
                "{\"sourceWorkoutId\":\"" + finished + "\",\"id\":\"" + saveTarget + "\",\"name\":\"Saved from source\",\"childIds\":[{\"sourceExerciseId\":\"" + finishedEntry + "\",\"id\":\"" + saveEntry + "\",\"sets\":[{\"sourceSetId\":\"" + finishedSet + "\",\"id\":\"" + saveSet + "\"}]}]}");

        String missingPerformSource = uid(), missingSaveSource = uid(), rejectedPerformTarget = uid();
        String rejectedPerform = copyMutation(uid(), "workout.performAgain", "workout", rejectedPerformTarget,
                "{\"sourceWorkoutId\":\"" + missingPerformSource + "\",\"id\":\"" + rejectedPerformTarget + "\",\"startedAt\":\"2025-06-03T00:00:00Z\",\"childIds\":[]}");
        String draft = uid(), draftEntry = uid(), draftTarget = uid();
        String draftInput = "{\"id\":\"" + draft + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + draftEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}";
        assertTrue(send(c.token, envelope(mutation(uid(), draft, draftInput))).body.contains("accepted"));
        String rejectedRoutineStart = copyMutation(uid(), "routine.startWorkout", "workout", draftTarget,
                "{\"sourceRoutineId\":\"" + draft + "\",\"id\":\"" + draftTarget + "\",\"startedAt\":\"2025-06-04T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + draftEntry + "\",\"id\":\"" + uid() + "\",\"sets\":[]}]} ");
        String rejectedSaveTarget = uid();
        String rejectedSave = copyMutation(uid(), "workout.saveAsRoutine", "routine", rejectedSaveTarget,
                "{\"sourceWorkoutId\":\"" + missingSaveSource + "\",\"id\":\"" + rejectedSaveTarget + "\",\"name\":\"Never here\",\"childIds\":[]}");

        int port = freePort();
        String performOriginal, startOriginal, saveOriginal, rejectPerformOriginal, rejectRoutineOriginal, rejectSaveOriginal;
        Process first = startServer(port);
        try {
            awaitServer(port);
            performOriginal = sendAt(port, c.token, envelope(perform));
            startOriginal = sendAt(port, c.token, envelope(startRoutine));
            saveOriginal = sendAt(port, c.token, envelope(saveAsRoutine));
            rejectPerformOriginal = sendAt(port, c.token, envelope(rejectedPerform));
            rejectRoutineOriginal = sendAt(port, c.token, envelope(rejectedRoutineStart));
            rejectSaveOriginal = sendAt(port, c.token, envelope(rejectedSave));
            assertEquals("accepted", parse(performOriginal).path("results").get(0).path("status").asText());
            assertEquals("accepted", parse(startOriginal).path("results").get(0).path("status").asText());
            assertEquals("accepted", parse(saveOriginal).path("results").get(0).path("status").asText());
            assertEquals("not_found", parse(rejectPerformOriginal).path("results").get(0).path("errorCode").asText());
            assertEquals("invalid_state", parse(rejectRoutineOriginal).path("results").get(0).path("errorCode").asText());
            assertEquals("not_found", parse(rejectSaveOriginal).path("results").get(0).path("errorCode").asText());
        } finally {
            stopServer(first);
        }

        // Make each rejected source newly eligible and remove the successful operations' sources.
        String missingPerformEntry = uid(), missingPerformSet = uid();
        String importedMissingPerform = "{\"id\":\"" + missingPerformSource + "\",\"name\":\"Now available\",\"status\":\"finished\",\"startedAt\":\"2025-06-05T00:00:00Z\",\"finishedAt\":\"2025-06-05T01:00:00Z\",\"exercises\":[{\"id\":\"" + missingPerformEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + missingPerformSet + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1,\"isCompleted\":true}]}]}";
        String missingSaveEntry = uid(), missingSaveSet = uid();
        String importedMissingSave = "{\"id\":\"" + missingSaveSource + "\",\"name\":\"Now available too\",\"status\":\"finished\",\"startedAt\":\"2025-06-06T00:00:00Z\",\"finishedAt\":\"2025-06-06T01:00:00Z\",\"exercises\":[{\"id\":\"" + missingSaveEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + missingSaveSet + "\",\"position\":0,\"repetitions\":2,\"weightKg\":2,\"isCompleted\":true}]}]}";
        assertTrue(send(c.token, envelope(importMutation(uid(), "workout", missingPerformSource, importedMissingPerform))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(importMutation(uid(), "workout", missingSaveSource, importedMissingSave))).body.contains("accepted"));
        assertTrue(send(c.token, envelope("{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + draft + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Now saved\"}}")).body.contains("accepted"));
        assertTrue(send(c.token, envelope(deleteMutation(uid(), "workout", finished))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(deleteMutation(uid(), "routine", savedRoutine))).body.contains("accepted"));
        String laterActive = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), laterActive, "later winner"))).body.contains("accepted"));

        int evidenceBeforeReplay = jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account);
        int batchesBeforeReplay = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        Process second = startServer(port);
        try {
            awaitServer(port);
            String[] originals = {performOriginal, startOriginal, saveOriginal};
            String[] acceptedRequests = {perform, startRoutine, saveAsRoutine};
            for (int i = 0; i < acceptedRequests.length; i++) {
                var originalOutcome = parse(originals[i]).path("results").get(0);
                var duplicate = parse(sendAt(port, c.token, envelope(acceptedRequests[i]))).path("results").get(0);
                assertEquals("duplicate", duplicate.path("status").asText());
                assertEquals(originalOutcome.path("batch"), duplicate.path("batch"));
            }
            String[] rejectedRequests = {rejectedPerform, rejectedRoutineStart, rejectedSave};
            String[] rejectedOriginals = {rejectPerformOriginal, rejectRoutineOriginal, rejectSaveOriginal};
            for (int i = 0; i < rejectedRequests.length; i++) {
                var duplicate = parse(sendAt(port, c.token, envelope(rejectedRequests[i]))).path("results").get(0);
                assertEquals("duplicate", duplicate.path("status").asText());
                assertEquals(parse(rejectedOriginals[i]).path("results").get(0).path("errorCode"), duplicate.path("errorCode"));
                assertTrue(duplicate.path("batch").isNull());
            }
            assertEquals("later winner", jdbc.queryForObject("select name from workouts where id=? and deleted_at is null", String.class, UUID.fromString(laterActive)));
            assertEquals(1, jdbc.queryForObject("select count(*) from workouts where owner_id=? and status='active' and deleted_at is null", Integer.class, c.account));
            assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(missingPerformSource)));
            assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(missingSaveSource)));
            assertFalse(jdbc.queryForObject("select is_draft from routines where id=?", Boolean.class, UUID.fromString(draft)));
            assertEquals(evidenceBeforeReplay, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
            assertEquals(batchesBeforeReplay, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        } finally {
            stopServer(second);
        }
    }

    @Test
    void distinctConcurrentCopyStartsCommitWholeReplacementBatchesAndPreserveBothSources() throws Exception {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String finished = finishedWorkout(c, exercise, "Finished concurrent source");
        String finishedEntry = workoutEntry(c, finished), finishedSet = workoutSet(c, finished);
        long finishedRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(finished));

        String routine = uid(), routineEntry = uid(), routineSet = uid();
        String routineInput = "{\"id\":\"" + routine + "\",\"name\":\"Concurrent routine\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":55,\"sets\":[{\"id\":\"" + routineSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        assertTrue(send(c.token, envelope(mutation(uid(), routine, routineInput))).body.contains("accepted"));
        assertTrue(send(c.token, envelope("{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routine + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Concurrent routine\"}}")).body.contains("accepted"));
        long routineRevision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(routine));
        String activeBefore = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), activeBefore, "Initial active"))).body.contains("accepted"));

        String performTarget = uid(), performEntry = uid(), performSet = uid();
        String perform = copyMutation(uid(), "workout.performAgain", "workout", performTarget,
                "{\"sourceWorkoutId\":\"" + finished + "\",\"id\":\"" + performTarget + "\",\"startedAt\":\"2025-10-01T00:00:00Z\",\"childIds\":[" + cloneBinding(finishedEntry, performEntry, finishedSet, performSet) + "]}");
        String routineTarget = uid(), routineTargetEntry = uid(), routineTargetSet = uid();
        String startRoutine = copyMutation(uid(), "routine.startWorkout", "workout", routineTarget,
                "{\"sourceRoutineId\":\"" + routine + "\",\"id\":\"" + routineTarget + "\",\"startedAt\":\"2025-10-02T00:00:00Z\",\"childIds\":[" + cloneBinding(routineEntry, routineTargetEntry, routineSet, routineTargetSet) + "]}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Response performResponse, routineResponse;
        try {
            Future<Response> performFuture = pool.submit(() -> {
                gate.await();
                return send(c.token, envelope(perform));
            });
            Future<Response> routineFuture = pool.submit(() -> {
                gate.await();
                return send(c.token, envelope(startRoutine));
            });
            gate.countDown();
            performResponse = performFuture.get(30, TimeUnit.SECONDS);
            routineResponse = routineFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        var performOutcome = parse(performResponse.body).path("results").get(0);
        var routineOutcome = parse(routineResponse.body).path("results").get(0);
        assertEquals(200, performResponse.status, performResponse.body);
        assertEquals(200, routineResponse.status, routineResponse.body);
        assertEquals("accepted", performOutcome.path("status").asText());
        assertEquals("accepted", routineOutcome.path("status").asText());
        var performChanges = performOutcome.path("batch").path("changes");
        var routineChanges = routineOutcome.path("batch").path("changes");
        assertEquals(2, performChanges.size());
        assertEquals(2, routineChanges.size());
        for (var changes : List.of(performChanges, routineChanges)) {
            assertEquals("delete", changes.get(0).path("operation").asText());
            assertEquals("upsert", changes.get(1).path("operation").asText());
            assertTrue(changes.get(0).path("revision").asLong() > 0);
            assertTrue(changes.get(1).path("revision").asLong() > 0);
        }
        var firstChanges = performChanges.get(0).path("aggregateId").asText().equals(activeBefore) ? performChanges : routineChanges;
        var secondChanges = firstChanges == performChanges ? routineChanges : performChanges;
        String firstCopyId = firstChanges.get(1).path("aggregateId").asText();
        String finalCopyId = secondChanges.get(1).path("aggregateId").asText();
        assertEquals(activeBefore, firstChanges.get(0).path("aggregateId").asText());
        assertEquals(firstCopyId, secondChanges.get(0).path("aggregateId").asText());
        assertEquals(Set.of(performTarget, routineTarget), Set.of(firstCopyId, finalCopyId));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where owner_id=? and status='active' and deleted_at is null", Integer.class, c.account));
        assertEquals(finalCopyId, activeWorkout(c));
        assertTrue(jdbc.queryForObject("select deleted_at is not null from workouts where id=?", Boolean.class, UUID.fromString(firstCopyId)));
        assertFalse(jdbc.queryForObject("select deleted_at is not null from workouts where id=?", Boolean.class, UUID.fromString(finalCopyId)));
        var performedRecord = List.of(performOutcome, routineOutcome).stream().filter(o -> o.path("batch").path("changes").get(1).path("aggregateId").asText().equals(performTarget)).findFirst().orElseThrow().path("batch").path("changes").get(1).path("record");
        assertEquals(performEntry, performedRecord.path("exercises").get(0).path("id").asText());
        assertEquals(performSet, performedRecord.path("exercises").get(0).path("sets").get(0).path("id").asText());
        assertFalse(performedRecord.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        var startedRecord = List.of(performOutcome, routineOutcome).stream().filter(o -> o.path("batch").path("changes").get(1).path("aggregateId").asText().equals(routineTarget)).findFirst().orElseThrow().path("batch").path("changes").get(1).path("record");
        assertEquals(routineTargetEntry, startedRecord.path("exercises").get(0).path("id").asText());
        assertEquals(routineTargetSet, startedRecord.path("exercises").get(0).path("sets").get(0).path("id").asText());
        assertTrue(startedRecord.path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertFalse(startedRecord.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(finished)));
        assertEquals(finishedRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(finished)));
        assertEquals("Concurrent routine", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(routine)));
        assertEquals(routineRevision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(routine)));
    }

    @Test
    void concurrentIdenticalPerformAgainCreatesOneCopyAndReplaysSameCanonicalBatch() throws Exception {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String source = finishedWorkout(c, exercise, "Concurrent copy source");
        String target = uid(), targetEntry = uid(), targetSet = uid();
        String sourceEntry = workoutEntry(c, source), sourceSet = workoutSet(c, source);
        String bindings = "[{\"sourceExerciseId\":\"" + sourceEntry + "\",\"id\":\"" + targetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + sourceSet + "\",\"id\":\"" + targetSet + "\"}]}]";
        String request = copyMutation(uid(), "workout.performAgain", "workout", target,
                "{\"sourceWorkoutId\":\"" + source + "\",\"id\":\"" + target + "\",\"startedAt\":\"2025-05-06T07:08:09Z\",\"childIds\":" + bindings + "}");
        String body = envelope(request);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Response> first = pool.submit(() -> {
                gate.await();
                return send(c.token, body);
            });
            Future<Response> second = pool.submit(() -> {
                gate.await();
                return send(c.token, body);
            });
            gate.countDown();
            var one = parse(first.get(30, TimeUnit.SECONDS).body).path("results").get(0);
            var two = parse(second.get(30, TimeUnit.SECONDS).body).path("results").get(0);
            assertEquals(Set.of("accepted", "duplicate"), Set.of(one.path("status").asText(), two.path("status").asText()));
            assertEquals(one.path("batch"), two.path("batch"));
            var changes = one.path("batch").path("changes");
            assertEquals(1, changes.size());
            var record = changes.get(0).path("record");
            assertEquals(target, record.path("id").asText());
            assertEquals("active", record.path("status").asText());
            assertEquals("Concurrent copy source", record.path("name").asText());
            assertEquals(targetEntry, record.path("exercises").get(0).path("id").asText());
            assertEquals(targetSet, record.path("exercises").get(0).path("sets").get(0).path("id").asText());
            assertEquals(8, record.path("exercises").get(0).path("sets").get(0).path("repetitions").asInt());
            assertFalse(record.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
            assertTrue(record.path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and owner_id=? and status='active' and deleted_at is null", Integer.class, UUID.fromString(target), c.account));
        assertEquals(1, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(target)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id where e.workout_id=? and s.id=? and not s.is_completed and s.rest_seconds is null", Integer.class, UUID.fromString(target), UUID.fromString(targetSet)));
        assertEquals("finished", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(source)));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=? and mutation_id=?", Integer.class, c.account, UUID.fromString(parse(request).path("mutationId").asText())));
    }

    @Test
    void unknownPostWriteCheckConstraintIsInfrastructureFailureForAllCopyCommands() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        List<org.junit.jupiter.api.function.Executable> evidence = new ArrayList<>();

        // performAgain: a finished source, an active root to replace, and fresh target IDs.
        String performSource = finishedWorkout(c, exercise, "perform-source");
        String oldActive = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), oldActive, "active before performAgain"))).status);
        String performTarget = uid(), performEntry = uid(), performSet = uid();
        String performBindings = "[{\"sourceExerciseId\":\"" + workoutEntry(c, performSource) + "\",\"id\":\"" + performEntry + "\",\"sets\":[{\"sourceSetId\":\"" + workoutSet(c, performSource) + "\",\"id\":\"" + performSet + "\"}]}]";
        String perform = copyMutation(uid(), "workout.performAgain", "workout", performTarget,
                "{\"sourceWorkoutId\":\"" + performSource + "\",\"id\":\"" + performTarget + "\",\"startedAt\":\"2025-02-01T00:00:00Z\",\"childIds\":" + performBindings + "}");
        evidence.addAll(failUnknownCheckAndRetry(c, "workout", performTarget, performSource, oldActive, List.of(performEntry, performSet), perform));

        // routine.startWorkout: saved routine with an exercise (and a nullable/zero template set).
        String routineSource = uid(), routineEntry = uid(), routineSet = uid();
        String routineInput = "{\"id\":\"" + routineSource + "\",\"name\":\"Start source\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + routineSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        assertTrue(send(c.token, envelope(mutation(uid(), routineSource, routineInput))).body.contains("accepted"));
        String routineSave = "{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + routineSource + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Start source\"}}";
        assertTrue(send(c.token, envelope(routineSave)).body.contains("accepted"));
        String activeBeforeRoutineCopy = activeWorkout(c);
        String routineTarget = uid(), routineTargetEntry = uid(), routineTargetSet = uid();
        String routineBindings = "[{\"sourceExerciseId\":\"" + routineEntry + "\",\"id\":\"" + routineTargetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + routineSet + "\",\"id\":\"" + routineTargetSet + "\"}]}]";
        String startFromRoutine = copyMutation(uid(), "routine.startWorkout", "workout", routineTarget,
                "{\"sourceRoutineId\":\"" + routineSource + "\",\"id\":\"" + routineTarget + "\",\"startedAt\":\"2025-02-02T00:00:00Z\",\"childIds\":" + routineBindings + "}");
        evidence.addAll(failUnknownCheckAndRetry(c, "workout", routineTarget, routineSource, activeBeforeRoutineCopy,
                List.of(routineTargetEntry, routineTargetSet), startFromRoutine));

        // saveAsRoutine: another eligible finished source; any active workout must remain untouched.
        String saveSource = finishedWorkout(c, exercise, "save-source");
        String activeBeforeSave = activeWorkout(c);
        String routineCopyTarget = uid(), savedEntry = uid(), savedSet = uid();
        String saveBindings = "[{\"sourceExerciseId\":\"" + workoutEntry(c, saveSource) + "\",\"id\":\"" + savedEntry + "\",\"sets\":[{\"sourceSetId\":\"" + workoutSet(c, saveSource) + "\",\"id\":\"" + savedSet + "\"}]}]";
        String saveAsRoutine = copyMutation(uid(), "workout.saveAsRoutine", "routine", routineCopyTarget,
                "{\"sourceWorkoutId\":\"" + saveSource + "\",\"id\":\"" + routineCopyTarget + "\",\"name\":\"Copied routine\",\"childIds\":" + saveBindings + "}");
        evidence.addAll(failUnknownCheckAndRetry(c, "routine", routineCopyTarget, saveSource, activeBeforeSave,
                List.of(savedEntry, savedSet), saveAsRoutine));

        assertAll("unknown SQLSTATE 23514 from test-only post-write constraint must remain an infrastructure error for every copy path", evidence);
    }

    private List<org.junit.jupiter.api.function.Executable> failUnknownCheckAndRetry(
            Client c, String targetType, String targetId, String sourceId, String expectedActiveId,
            List<String> targetChildren, String request) {
        String table = targetType.equals("workout") ? "workouts" : "routines";
        UUID target = UUID.fromString(targetId), source = UUID.fromString(sourceId);
        long cursorBefore = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        int batchesBefore = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        String sourceTable = parse(request).path("type").asText().equals("routine.startWorkout") ? "routines" : "workouts";
        long sourceRevisionBefore = jdbc.queryForObject("select revision from " + sourceTable + " where id=?", Long.class, source);
        String function = "qa_5g_unknown_check_fault";
        String trigger = "qa_5g_unknown_check_fault";
        String setTable = targetType.equals("workout") ? "workout_sets" : "routine_sets";
        String targetSetId = targetChildren.getLast();
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin if new.id = '" + targetSetId + "'::uuid then raise exception 'qa injected unknown post-write check' using errcode='23514', constraint='qa_unknown_copy_constraint'; end if; return new; end $$");
        jdbc.execute("create trigger " + trigger + " after insert on " + setTable + " for each row execute function " + function + "()");
        Response failed;
        try {
            failed = send(c.token, envelope(request));
        } finally {
            jdbc.execute("drop trigger " + trigger + " on " + setTable);
            jdbc.execute("drop function " + function + "()");
        }

        int targetRootsAfterFault = jdbc.queryForObject("select count(*) from " + table + " where id=?", Integer.class, target);
        int targetRootReservations = jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_id=?", Integer.class, target);
        int targetChildReservations = jdbc.queryForObject("select count(*) from child_id_reservations where root_id=? and child_id in (" + String.join(",", Collections.nCopies(targetChildren.size(), "?")) + ")", Integer.class,
                prepend(target, targetChildren.stream().map(UUID::fromString).toList()).toArray());
        long sourceRevisionAfter = jdbc.queryForObject("select revision from " + sourceTable + " where id=?", Long.class, source);
        String activeAfter = activeWorkout(c);
        long cursorAfter = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, c.account);
        int batchesAfter = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account);
        int replayEvidenceAfter = jdbc.queryForObject("select count(*) from processed_mutations where owner_id=? and mutation_id=?", Integer.class, c.account, UUID.fromString(parse(request).path("mutationId").asText()));
        Response retry = send(c.token, envelope(request));

        return List.of(
                () -> assertEquals(500, failed.status, "unknown constraint must be sanitized HTTP 500, not a durable domain rejection; observed body=" + failed.body),
                () -> assertEquals("{\"errorCode\":\"internal_error\"}", failed.body),
                () -> assertEquals(0, targetRootsAfterFault, "failed copy must not leave target rows"),
                () -> assertEquals(0, targetRootReservations, "failed copy must not reserve its target root"),
                () -> assertEquals(0, targetChildReservations, "failed copy must not reserve target children"),
                () -> assertEquals(sourceRevisionBefore, sourceRevisionAfter, "copy must leave source revision unchanged"),
                () -> assertEquals(expectedActiveId, activeAfter, "failed copy must preserve prior active workout"),
                () -> assertEquals(cursorBefore, cursorAfter),
                () -> assertEquals(batchesBefore, batchesAfter),
                () -> assertEquals(0, replayEvidenceAfter, "unexpected SQL failure must not persist replay evidence"),
                () -> assertEquals("accepted", parse(retry.body).path("results").get(0).path("status").asText(), "after transient test fault removal, identical request must execute successfully; got " + retry.body)
        );
    }

    private List<Object> prepend(Object first, List<?> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private String copyMutation(String id, String type, String aggregateType, String target, String payload) {
        return "{\"mutationId\":\"" + id + "\",\"type\":\"" + type + "\",\"aggregateType\":\"" + aggregateType + "\",\"aggregateId\":\"" + target + "\",\"localGeneration\":1,\"payload\":" + payload + "}";
    }

    private String finishedWorkout(Client c, String exercise, String name) {
        String root = uid(), entry = uid(), set = uid();
        assertEquals(200, send(c.token, envelope(startMutation(uid(), root, name))).status);
        String exercises = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"notes\":\"source note\",\"restDurationSeconds\":60,\"sets\":[{\"id\":\"" + set + "\",\"position\":0,\"repetitions\":8,\"weightKg\":0,\"isCompleted\":true,\"restSeconds\":13}]}]";
        String active = "{\"id\":\"" + root + "\",\"name\":\"" + name + "\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":" + exercises + "}";
        assertTrue(send(c.token, envelope(workoutMutation(uid(), root, active, 2))).body.contains("accepted"));
        assertTrue(send(c.token, envelope(finishMutation(uid(), root))).body.contains("accepted"));
        return root;
    }

    private String workoutEntry(Client c, String root) {
        return jdbc.queryForObject("select id from workout_exercises where workout_id=? order by position limit 1", UUID.class, UUID.fromString(root)).toString();
    }

    private String workoutSet(Client c, String root) {
        return jdbc.queryForObject("select s.id from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id where e.workout_id=? order by e.position,s.position limit 1", UUID.class, UUID.fromString(root)).toString();
    }

    private String activeWorkout(Client c) {
        List<UUID> ids = jdbc.query("select id from workouts where owner_id=? and status='active' and deleted_at is null", (rs, row) -> rs.getObject(1, UUID.class), c.account);
        assertTrue(ids.size() <= 1, "account must never have more than one active workout");
        return ids.isEmpty() ? null : ids.getFirst().toString();
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String workoutMutation(String mutationId, String workoutId, String payload, int generation) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":" + generation + ",\"payload\":" + payload + "}";
    }

    private String finishMutation(String mutationId, String workoutId) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
    }

    private String startMutation(String mutationId, String workoutId, String name) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"workout.start\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + workoutId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + workoutId + "\",\"name\":\"" + name + "\",\"startedAt\":\"2025-01-01T00:00:00Z\"}}";
    }

    @Test
    void childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp() {
        Client c = client();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        for (boolean workout : List.of(false, true)) {
            for (int deletedPosition : List.of(0, 1, 2)) {
                String root = uid();
                List<String> entries = List.of(uid(), uid(), uid());
                String children = threeEntries(workout, entries, exercise);
                if (workout) {
                    assertTrue(send(c.token, envelope(startMutation(uid(), root, "W"))).body.contains("accepted"));
                    assertTrue(send(c.token, envelope(workoutUpsert(uid(), root, children))).body.contains("accepted"));
                } else
                    assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + children + "}"))).body.contains("accepted"));
                String child = entries.get(deletedPosition);
                String command = childMutation(workout, false, uid(), root, child, null);
                Response removed = send(c.token, envelope(command));
                assertEquals(200, removed.status, removed.body);
                assertEquals("accepted", parse(removed.body).path("results").get(0).path("status").asText());
                var record = parse(removed.body).path("results").get(0).path("batch").path("changes").get(0).path("record");
                assertEquals(2, record.path("exercises").size());
                List<String> expected = new ArrayList<>(entries);
                expected.remove(deletedPosition);
                for (int i = 0; i < expected.size(); i++) {
                    assertEquals(expected.get(i), record.path("exercises").get(i).path("id").asText());
                    assertEquals(i, record.path("exercises").get(i).path("position").asInt());
                }
            }
            for (int deletedPosition : List.of(0, 1, 2)) {
                String root = uid(), entry = uid();
                String sets = "[" + setJson(uid(), 0, workout) + "," + setJson(uid(), 1, workout) + "," + setJson(uid(), 2, workout) + "]";
                String one = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0," + (workout ? "\"notes\":\"keep\"," : "") + "\"restDurationSeconds\":47,\"sets\":" + sets + "}]";
                if (workout) {
                    assertTrue(send(c.token, envelope(startMutation(uid(), root, "W"))).body.contains("accepted"));
                    assertTrue(send(c.token, envelope(workoutUpsert(uid(), root, one))).body.contains("accepted"));
                } else
                    assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + one + "}"))).body.contains("accepted"));
                String setTable = workout ? "workout_sets" : "routine_sets", parentColumn = workout ? "workout_exercise_id" : "routine_exercise_id";
                String setId = jdbc.queryForObject("select id from " + setTable + " where " + parentColumn + "=? order by position offset ? limit 1", UUID.class, UUID.fromString(entry), deletedPosition).toString();
                List<UUID> survivors = jdbc.query("select id from " + setTable + " where " + parentColumn + "=? and id<>? order by position", (rs, n) -> rs.getObject(1, UUID.class), UUID.fromString(entry), UUID.fromString(setId));
                Response removed = send(c.token, envelope(childMutation(workout, true, uid(), root, setId, null)));
                assertEquals(200, removed.status, removed.body);
                var record = parse(removed.body).path("results").get(0).path("batch").path("changes").get(0).path("record");
                assertEquals(1, record.path("exercises").size(), "deleting a set must retain its parent entry");
                var canonicalSets = record.path("exercises").get(0).path("sets");
                assertEquals(2, canonicalSets.size());
                for (int i = 0; i < 2; i++) {
                    assertEquals(i, canonicalSets.get(i).path("position").asInt());
                    assertEquals(survivors.get(i).toString(), canonicalSets.get(i).path("id").asText());
                    assertEquals(8, canonicalSets.get(i).path("repetitions").asInt());
                    assertEquals(40.5, canonicalSets.get(i).path("weightKg").asDouble());
                }
                assertEquals(1, jdbc.queryForObject("select count(*) from child_id_reservations where family=? and child_id=? and root_id=? and role='set' and parent_id=?", Integer.class, workout ? "workout" : "routine", UUID.fromString(setId), UUID.fromString(root), UUID.fromString(entry)));
                if (workout) assertEquals("keep", record.path("exercises").get(0).path("notes").asText());
            }
        }
    }

    @Test
    void routineSetDeleteKeepsSavedStateAndRejectedRootChildReferenceAndExerciseAreNoEffect() {
        Client a = client(), b = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), entry = uid(), set = uid();
        String input = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[" + setJson(set, 0, false) + "]}]";
        assertTrue(send(a.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"Draft\",\"isDraft\":true,\"exercises\":" + input + "}"))).body.contains("accepted"));
        assertTrue(send(a.token, envelope("{\"mutationId\":\"" + uid() + "\",\"type\":\"routine.save\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + root + "\",\"localGeneration\":2,\"payload\":{\"name\":\"Saved\"}}")).body.contains("accepted"));
        String del = childMutation(false, true, uid(), root, set, null);
        Response accepted = send(a.token, envelope(del));
        assertEquals("accepted", parse(accepted.body).path("results").get(0).path("status").asText());
        assertFalse(parse(accepted.body).path("results").get(0).path("batch").path("changes").get(0).path("record").path("isDraft").asBoolean());
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_sets where id=?", Integer.class, UUID.fromString(set)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=?", Integer.class, UUID.fromString(entry)));
        long revision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root));
        String missingEntry = childMutation(false, false, uid(), root, uid(), null);
        String foreignEntry = uid(), foreignRoot = uid();
        assertTrue(send(b.token, envelope(mutation(uid(), foreignRoot, "{\"id\":\"" + foreignRoot + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + foreignEntry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]}"))).body.contains("accepted"));
        String foreignChild = childMutation(false, false, uid(), root, foreignEntry, null);
        String workoutRoot = uid(), workoutEntry = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), workoutRoot, "W"))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(workoutUpsert(uid(), workoutRoot, "[{\"id\":\"" + workoutEntry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]"))).body.contains("accepted"));
        String badRef = replaceMutation(uid(), workoutRoot, workoutEntry, "00000000-0000-0000-0000-000000000000");
        for (String cmd : List.of(missingEntry, foreignChild, badRef)) {
            String before = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", String.class, a.account);
            Response rejected = send(a.token, envelope(cmd));
            assertEquals("rejected", parse(rejected.body).path("results").get(0).path("status").asText());
            assertEquals(before, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", String.class, a.account));
            assertEquals(revision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root)));
            assertTrue(send(a.token, envelope(cmd)).body.contains("duplicate"));
        }
        String otherRoot = uid(), otherEntry = uid(), otherSet = uid();
        String otherShape = "[{\"id\":\"" + otherEntry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[" + setJson(otherSet, 0, false) + "]}]";
        assertTrue(send(a.token, envelope(mutation(uid(), otherRoot, "{\"id\":\"" + otherRoot + "\",\"name\":\"Other\",\"isDraft\":true,\"exercises\":" + otherShape + "}"))).body.contains("accepted"));
        for (String cross : List.of(childMutation(false, false, uid(), root, otherEntry, null), childMutation(false, true, uid(), root, otherSet, null))) {
            var rejected = parse(send(a.token, envelope(cross)).body).path("results").get(0);
            assertEquals("not_found", rejected.path("errorCode").asText());
            assertEquals(revision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=?", Integer.class, UUID.fromString(otherEntry)));
            assertEquals(1, jdbc.queryForObject("select count(*) from routine_sets where id=?", Integer.class, UUID.fromString(otherSet)));
        }
    }

    @Test
    void childCommandDatabaseFaultAfterRowMutationRollsBackRootRevisionBatchAndEvidence() {
        Client c = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), entry = uid();
        String exercises = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + exercises + "}"))).body.contains("accepted"));
        long revision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root));
        String marker = "slice5d_test_failure", request = childMutation(false, false, uid(), root, entry, null);
        jdbc.execute("create function test_5d_batch_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger test_5d_batch_fault before insert on sync_change_batches for each row execute function test_5d_batch_fault()");
        try {
            Response response = send(c.token, envelope(request));
            assertEquals(500, response.status);
            assertEquals("{\"errorCode\":\"internal_error\"}", response.body);
            assertFalse(response.body.contains(marker));
            assertEquals(revision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=?", Integer.class, UUID.fromString(entry)));
            assertEquals(1, jdbc.queryForObject("select count(*) from account_sync_state s join routines r on r.owner_id=s.account_id where r.id=? and s.committed_cursor=1", Integer.class, UUID.fromString(root)));
            assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where mutation_id=?", Integer.class, UUID.fromString(parseMutationId(request))));
        } finally {
            jdbc.execute("drop trigger test_5d_batch_fault on sync_change_batches");
            jdbc.execute("drop function test_5d_batch_fault()");
        }
    }

    private String parseMutationId(String raw) {
        return parse(raw).path("mutationId").asText();
    }

    private String setJson(String id, int position, boolean workout) {
        return "{\"id\":\"" + id + "\",\"position\":" + position + ",\"repetitions\":8,\"weightKg\":40.5" + (workout ? ",\"isCompleted\":false" : "") + "}";
    }

    private String threeEntries(boolean workout, List<String> ids, String exercise) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            out.add("{\"id\":\"" + ids.get(i) + "\",\"exerciseId\":\"" + exercise + "\",\"position\":" + i + "," + (workout ? "\"notes\":\"note" + i + "\"," : "") + "\"restDurationSeconds\":" + (30 + i) + ",\"sets\":[" + setJson(uid(), 0, workout) + "]}");
        return "[" + String.join(",", out) + "]";
    }

    private String workoutUpsert(String mid, String root, String exercises) {
        return "{\"mutationId\":\"" + mid + "\",\"type\":\"workout.upsert\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":2,\"payload\":{\"id\":\"" + root + "\",\"name\":\"W\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":" + exercises + "}}";
    }

    private String replaceMutation(String mid, String root, String entry, String exercise) {
        return "{\"mutationId\":\"" + mid + "\",\"type\":\"workout.replaceExercise\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":3,\"payload\":{\"workoutExerciseId\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\"}}";
    }

    private String childMutation(boolean workout, boolean set, String mid, String root, String child, String exercise) {
        String kind = workout ? (set ? "workout.deleteSet" : "workout.deleteExercise") : (set ? "routine.deleteSet" : "routine.deleteExercise");
        String key = workout ? (set ? "workoutSetId" : "workoutExerciseId") : (set ? "routineSetId" : "routineExerciseId");
        String payload = "{\"" + key + "\":\"" + child + "\"}";
        if (exercise != null)
            payload = payload.substring(0, payload.length() - 1) + ",\"exerciseId\":\"" + exercise + "\"}";
        return "{\"mutationId\":\"" + mid + "\",\"type\":\"" + kind + "\",\"aggregateType\":\"" + (workout ? "workout" : "routine") + "\",\"aggregateId\":\"" + root + "\",\"localGeneration\":3,\"payload\":" + payload + "}";
    }

    @Test
    void workoutReplacePreservesEntryAndAllSetValuesAndUpdatesCanonicalNameOnly() {
        Client c = client();
        List<UUID> exercises = jdbc.query("select id from exercises where owner_id is null order by id limit 2", (rs, n) -> rs.getObject(1, UUID.class));
        String root = uid(), entry = uid(), s0 = uid(), s1 = uid();
        assertTrue(send(c.token, envelope(startMutation(uid(), root, "W"))).body.contains("accepted"));
        String input = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercises.get(0) + "\",\"position\":0,\"notes\":\"do not change\",\"restDurationSeconds\":73,\"sets\":[{\"id\":\"" + s0 + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0.0,\"isCompleted\":false,\"restSeconds\":null},{\"id\":\"" + s1 + "\",\"position\":1,\"repetitions\":9,\"weightKg\":12.5,\"isCompleted\":true,\"restSeconds\":4}]}]";
        assertTrue(send(c.token, envelope(workoutUpsert(uid(), root, input))).body.contains("accepted"));
        String request = replaceMutation(uid(), root, entry, exercises.get(1).toString());
        Response response = send(c.token, envelope(request));
        assertEquals(200, response.status, response.body);
        var outcome = parse(response.body).path("results").get(0);
        assertEquals("accepted", outcome.path("status").asText());
        var record = outcome.path("batch").path("changes").get(0).path("record");
        var actual = record.path("exercises").get(0);
        assertEquals(entry, actual.path("id").asText());
        assertEquals(exercises.get(1).toString(), actual.path("exerciseId").asText());
        assertEquals(0, actual.path("position").asInt());
        assertEquals("do not change", actual.path("notes").asText());
        assertEquals(73, actual.path("restDurationSeconds").asInt());
        assertEquals(s0, actual.path("sets").get(0).path("id").asText());
        assertTrue(actual.path("sets").get(0).path("weightKg").isDouble());
        assertEquals(0.0, actual.path("sets").get(0).path("weightKg").asDouble());
        assertEquals(s1, actual.path("sets").get(1).path("id").asText());
        assertEquals(9, actual.path("sets").get(1).path("repetitions").asInt());
        assertEquals(12.5, actual.path("sets").get(1).path("weightKg").asDouble());
        assertTrue(actual.path("sets").get(1).path("isCompleted").asBoolean());
        assertEquals(4, actual.path("sets").get(1).path("restSeconds").asInt());
        assertEquals(jdbc.queryForObject("select name from exercises where id=?", String.class, exercises.get(1)), actual.path("exerciseName").asText());
        assertEquals(record.path("revision").asLong(), jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(root)));
        assertTrue(send(c.token, envelope(request)).body.contains("duplicate"));
        String second = replaceMutation(uid(), root, entry, exercises.get(0).toString());
        assertTrue(send(c.token, envelope(second)).body.contains("accepted"));
        assertEquals("duplicate", parse(send(c.token, envelope(request)).body).path("results").get(0).path("status").asText());
        assertEquals(exercises.get(0).toString(), canonical.readWorkout(c.account, UUID.fromString(root)).exercises().getFirst().value().exerciseId().toString());
    }

    @Test
    void childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren() {
        Client a = client(), b = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), entry = uid();
        String input = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + uid() + "\",\"position\":0,\"repetitions\":1,\"weightKg\":1.0,\"isCompleted\":true}]}]";
        assertTrue(send(a.token, envelope(startMutation(uid(), root, "W"))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(workoutUpsert(uid(), root, input))).body.contains("accepted"));
        String finish = "{\"mutationId\":\"" + uid() + "\",\"type\":\"workout.finish\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + root + "\",\"localGeneration\":3,\"payload\":{\"finishedAt\":\"2025-01-02T00:00:00Z\"}}";
        assertTrue(send(a.token, envelope(finish)).body.contains("accepted"));
        String finished = replaceMutation(uid(), root, entry, ex), before = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", String.class, a.account);
        assertTrue(send(a.token, envelope(finished)).body.contains("invalid_state"));
        assertEquals(before, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", String.class, a.account));
        assertTrue(send(a.token, envelope(replaceMutation(uid(), uid(), entry, ex))).body.contains("not_found"));
        String foreignRoot = uid(), foreignEntry = uid();
        assertTrue(send(b.token, envelope(startMutation(uid(), foreignRoot, "B"))).body.contains("accepted"));
        assertTrue(send(b.token, envelope(workoutUpsert(uid(), foreignRoot, "[{\"id\":\"" + foreignEntry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":1,\"sets\":[]}]"))).body.contains("accepted"));
        String current = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), current, "A"))).body.contains("accepted"));
        String cross = childMutation(true, false, uid(), current, foreignEntry, null);
        assertTrue(send(a.token, envelope(cross)).body.contains("not_found"));
        String missing = replaceMutation(uid(), current, uid(), ex);
        assertTrue(send(a.token, envelope(missing)).body.contains("not_found"));
        jdbc.update("update workouts set deleted_at=clock_timestamp() where id=?", UUID.fromString(current));
        assertTrue(send(a.token, envelope(replaceMutation(uid(), current, uid(), ex))).body.contains("not_found"));
        UUID[] ids = jdbc.query("select child_id from child_id_reservations where family='workout' and root_id=?", (rs, n) -> rs.getObject(1, UUID.class), UUID.fromString(root)).toArray(UUID[]::new);
        assertTrue(ids.length >= 2, "root and entry identity reservations remain durable");
    }

    @Test
    void rejectedMissingChildIsReplayedAfterItBecomesPresentAndForeignOrTombstonedExerciseDoesNotMutate() {
        Client a = client(), b = client();
        String bundled = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), missing = uid();
        assertTrue(send(a.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":[]}"))).body.contains("accepted"));
        String rejected = childMutation(false, false, uid(), root, missing, null);
        assertTrue(send(a.token, envelope(rejected)).body.contains("not_found"));
        String nowPresent = "[{\"id\":\"" + missing + "\",\"exerciseId\":\"" + bundled + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(a.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + nowPresent + "}"))).body.contains("accepted"));
        assertEquals("duplicate", parse(send(a.token, envelope(rejected)).body).path("results").get(0).path("status").asText());
        String foreignExercise = uid(), tombstonedExercise = uid();
        assertTrue(send(b.token, envelope(customMutation(uid(), foreignExercise, "Foreign", "\"secondaryMuscleGroupIds\":[]"))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(customMutation(uid(), tombstonedExercise, "Tombstone", "\"secondaryMuscleGroupIds\":[]"))).body.contains("accepted"));
        jdbc.update("update exercises set deleted_at=clock_timestamp() where id=?", UUID.fromString(tombstonedExercise));
        String workout = uid(), entry = uid();
        assertTrue(send(a.token, envelope(startMutation(uid(), workout, "W"))).body.contains("accepted"));
        assertTrue(send(a.token, envelope(workoutUpsert(uid(), workout, "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + bundled + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]"))).body.contains("accepted"));
        long rev = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(workout));
        for (String invalid : List.of(foreignExercise, tombstonedExercise)) {
            String cmd = replaceMutation(uid(), workout, entry, invalid);
            Response response = send(a.token, envelope(cmd));
            assertEquals("invalid_reference", parse(response.body).path("results").get(0).path("errorCode").asText());
            assertEquals(rev, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(workout)));
        }
        String foreignWorkout = uid(), foreignEntry = uid();
        assertTrue(send(b.token, envelope(startMutation(uid(), foreignWorkout, "B"))).body.contains("accepted"));
        assertTrue(send(b.token, envelope(workoutUpsert(uid(), foreignWorkout, "[{\"id\":\"" + foreignEntry + "\",\"exerciseId\":\"" + bundled + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}]"))).body.contains("accepted"));
        long cursor = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, a.account);
        for (String absentOrForeign : List.of(uid(), foreignEntry)) {
            String cmd = replaceMutation(uid(), workout, absentOrForeign, foreignExercise);
            var result = parse(send(a.token, envelope(cmd)).body).path("results").get(0);
            assertEquals("not_found", result.path("errorCode").asText());
            assertEquals(rev, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(workout)));
            assertEquals(cursor, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, a.account));
        }
    }

    @Test
    void concurrentDifferentChildDeletesSerializeWithoutLosingEitherChange() throws Exception {
        Client c = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid();
        List<String> entries = List.of(uid(), uid());
        String pair = "[{\"id\":\"" + entries.get(0) + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]},{\"id\":\"" + entries.get(1) + "\",\"exerciseId\":\"" + ex + "\",\"position\":1,\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + pair + "}"))).body.contains("accepted"));
        var current = canonical.readRoutine(c.account, UUID.fromString(root));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (var item : current.exercises())
                futures.add(pool.submit(() -> {
                    gate.await();
                    return send(c.token, envelope(childMutation(false, false, uid(), root, item.value().id().toString(), null)));
                }));
            gate.countDown();
            for (var f : futures)
                assertEquals("accepted", parse(f.get().body).path("results").get(0).path("status").asText());
            assertEquals(0, jdbc.queryForObject("select count(*) from routine_exercises where routine_id=?", Integer.class, UUID.fromString(root)));
            assertEquals(3, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void childDeleteOriginalResultSurvivesActualHttpRestartAndLaterAggregateWrite() {
        Client c = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), entry = uid();
        String original = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + original + "}"))).body.contains("accepted"));
        String command = childMutation(false, false, uid(), root, entry, null);
        int port = freePort();
        Process first = startServer(port);
        com.fasterxml.jackson.databind.JsonNode firstBatch;
        try {
            awaitServer(port);
            var accepted = parse(sendAt(port, c.token, envelope(command)));
            assertEquals("accepted", accepted.path("results").get(0).path("status").asText());
            firstBatch = accepted.path("results").get(0).path("batch");
        } finally {
            stopServer(first);
        }
        String later = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":55,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"later\",\"isDraft\":true,\"exercises\":" + later + "}"))).body.contains("accepted"));
        Process second = startServer(port);
        try {
            awaitServer(port);
            var duplicate = parse(sendAt(port, c.token, envelope(command)));
            assertEquals("duplicate", duplicate.path("results").get(0).path("status").asText());
            assertEquals(firstBatch, duplicate.path("results").get(0).path("batch"));
        } finally {
            stopServer(second);
        }
        assertEquals(55, jdbc.queryForObject("select rest_duration_seconds from routine_exercises where id=?", Integer.class, UUID.fromString(entry)));
        assertEquals(3, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
    }

    @Test
    void concurrentIdenticalChildCommandPublishesOneRevisionAndBatch() throws Exception {
        Client c = client();
        String ex = jdbc.queryForObject("select id from exercises where owner_id is null limit 1", UUID.class).toString();
        String root = uid(), entry = uid();
        String payload = "[{\"id\":\"" + entry + "\",\"exerciseId\":\"" + ex + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}]";
        assertTrue(send(c.token, envelope(mutation(uid(), root, "{\"id\":\"" + root + "\",\"name\":\"R\",\"isDraft\":true,\"exercises\":" + payload + "}"))).body.contains("accepted"));
        String command = childMutation(false, false, uid(), root, entry, null), body = envelope(command);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Response> one = pool.submit(() -> {
                gate.await();
                return send(c.token, body);
            });
            Future<Response> two = pool.submit(() -> {
                gate.await();
                return send(c.token, body);
            });
            gate.countDown();
            var a = parse(one.get().body).path("results").get(0);
            var b = parse(two.get().body).path("results").get(0);
            assertTrue(Set.of(a.path("status").asText(), b.path("status").asText()).containsAll(Set.of("accepted", "duplicate")));
            assertEquals(2, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(2, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, c.account));
            assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, c.account));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void accountsHaveIndependentDeduplicationNamespaces() {
        Client a = client(), b = client();
        String id = uid(), rootA = uid(), rootB = uid();
        String ma = mutation(id, rootA, "{\"id\":\"" + rootA + "\",\"name\":\"A\",\"isDraft\":true,\"exercises\":[]}");
        String mb = mutation(id, rootB, "{\"id\":\"" + rootB + "\",\"name\":\"B\",\"isDraft\":true,\"exercises\":[]}");
        assertTrue(send(a.token, envelope(ma)).body.contains("accepted"));
        assertTrue(send(b.token, envelope(mb)).body.contains("accepted"));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_mutations where mutation_id=?", Integer.class, UUID.fromString(id)));
    }
}
