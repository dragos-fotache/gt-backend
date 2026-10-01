package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncPullHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String BUNDLED_EXERCISE = "960b59d9-e989-4bf1-ad45-37f1e0868de2";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "pull-integration-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;

    private record Account(UUID id, UUID sessionId, String accessToken, String refreshToken) {}
    private record Reply(int status, byte[] bytes) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        JsonNode json() {
            try { return JSON.readTree(bytes); }
            catch (Exception e) { throw new AssertionError("Expected JSON response: " + text(), e); }
        }
    }

    private Account account() { return session(UUID.randomUUID(), "1 hour", false, false); }

    private Account session(UUID accountId, String lifetime, boolean revoked, boolean disabled) {
        if (jdbc.queryForObject("select count(*) from accounts where id=?", Integer.class, accountId) == 0)
            jdbc.update("insert into accounts(id) values(?)", accountId);
        UUID sessionId = UUID.randomUUID();
        String access = UUID.randomUUID().toString(), refresh = UUID.randomUUID().toString();
        jdbc.update("insert into auth_sessions(id,account_id,created_at,revoked_at) values(?,?,clock_timestamp(),?)",
                sessionId, accountId, revoked ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+(?::interval)),(?,?, 'refresh',clock_timestamp(),clock_timestamp()+interval '30 days')",
                crypto.digest(access), sessionId, lifetime, crypto.digest(refresh), sessionId);
        if (disabled) jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", accountId);
        return new Account(accountId, sessionId, access, refresh);
    }

    private String id() { return UUID.randomUUID().toString(); }

    private Reply request(Account account, String method, String path, String body, String idempotencyKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (account != null) builder.header("Authorization", "Bearer " + account.accessToken());
            if (body != null) builder.header("Content-Type", "application/json");
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    private Reply get(Account account, String path) { return request(account, "GET", path, null, null); }

    private Reply send(Account account, String method, String path, String body) {
        return request(account, method, path, body, UUID.randomUUID().toString());
    }

    private Reply pull(Account account, String cursor, Integer limit) {
        List<String> query = new ArrayList<>();
        if (cursor != null) query.add("cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        if (limit != null) query.add("limit=" + limit);
        return get(account, "/v1/sync/pull" + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    private String pushMutation(String mutationId, String aggregateId, String name) {
        return "{\"mutationId\":\"" + mutationId + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + aggregateId + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + aggregateId + "\",\"name\":\"" + name + "\"}}";
    }

    private JsonNode push(Account account, List<String> mutations) {
        Reply result = send(account, "POST", "/v1/sync/push", "{\"mutations\":[" + String.join(",", mutations) + "]}");
        assertEquals(200, result.status(), result.text());
        return result.json();
    }

    private void assertPullEnvelope(Reply reply) {
        assertEquals(200, reply.status(), reply.text());
        JsonNode body = reply.json();
        assertTrue(body.path("batches").isArray());
        assertTrue(body.path("nextCursor").isTextual() && !body.path("nextCursor").asText().isEmpty());
        assertTrue(body.path("hasMore").isBoolean());
        if (body.path("hasMore").asBoolean()) {
            assertFalse(body.path("batches").isEmpty(), "hasMore requires at least one whole batch of progress");
        }
        for (JsonNode batch : body.path("batches")) {
            assertTrue(batch.path("batchId").isTextual());
            assertTrue(batch.path("cursor").isTextual() && !batch.path("cursor").asText().isEmpty());
            assertTrue(batch.path("changes").isArray() && !batch.path("changes").isEmpty());
            for (JsonNode change : batch.path("changes")) assertChangeEnvelope(change);
        }
    }

    private void assertChangeEnvelope(JsonNode change) {
        String type = change.path("aggregateType").asText();
        assertTrue(Set.of("workout", "routine", "customExercise").contains(type), change.toString());
        assertTrue(change.path("aggregateId").isTextual());
        assertTrue(change.path("revision").asLong() > 0);
        String operation = change.path("operation").asText();
        assertTrue(Set.of("upsert", "delete").contains(operation));
        if (operation.equals("delete")) {
            assertTrue(change.path("record").isMissingNode() || change.path("record").isNull(),
                    "deletes have no live record; the optional field may be absent or null");
        } else {
            JsonNode record = change.path("record");
            assertEquals(change.path("aggregateId").asText(), record.path("id").asText());
            assertEquals(change.path("revision").asLong(), record.path("revision").asLong());
            assertFalse(record.has("ownerId"));
            assertFalse(record.has("owner_id"));
        }
    }

    private String accountState(UUID owner) {
        String ownerId = owner.toString();
        String sql = "select jsonb_build_object(" +
                "'workouts',(select jsonb_agg(to_jsonb(w) order by w.id) from workouts w where w.owner_id='" + ownerId + "'::uuid)," +
                "'workoutEntries',(select jsonb_agg(to_jsonb(e) order by e.workout_id,e.position) from workout_exercises e join workouts w on w.id=e.workout_id where w.owner_id='" + ownerId + "'::uuid)," +
                "'workoutSets',(select jsonb_agg(to_jsonb(s) order by e.workout_id,e.position,s.position) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id join workouts w on w.id=e.workout_id where w.owner_id='" + ownerId + "'::uuid)," +
                "'routines',(select jsonb_agg(to_jsonb(r) order by r.id) from routines r where r.owner_id='" + ownerId + "'::uuid)," +
                "'routineEntries',(select jsonb_agg(to_jsonb(e) order by e.routine_id,e.position) from routine_exercises e join routines r on r.id=e.routine_id where r.owner_id='" + ownerId + "'::uuid)," +
                "'routineSets',(select jsonb_agg(to_jsonb(s) order by e.routine_id,e.position,s.position) from routine_sets s join routine_exercises e on e.id=s.routine_exercise_id join routines r on r.id=e.routine_id where r.owner_id='" + ownerId + "'::uuid)," +
                "'customExercises',(select jsonb_agg(to_jsonb(e) order by e.id) from exercises e where e.owner_id='" + ownerId + "'::uuid)," +
                "'syncState',(select to_jsonb(s) from account_sync_state s where s.account_id='" + ownerId + "'::uuid)," +
                "'batches',(select jsonb_agg(to_jsonb(b) order by b.cursor) from sync_change_batches b where b.account_id='" + ownerId + "'::uuid)," +
                "'changes',(select jsonb_agg(to_jsonb(c) order by c.cursor,c.position) from sync_changes c where c.account_id='" + ownerId + "'::uuid)," +
                "'resourceEvidence',(select jsonb_agg(to_jsonb(m) order by m.idempotency_key) from resource_mutations m where m.owner_id='" + ownerId + "'::uuid)," +
                "'syncEvidence',(select jsonb_agg(to_jsonb(m) order by m.mutation_id) from processed_mutations m where m.owner_id='" + ownerId + "'::uuid)," +
                "'rootReservations',(select jsonb_agg(to_jsonb(r) order by r.aggregate_type,r.aggregate_id) from root_id_reservations r where r.owner_id='" + ownerId + "'::uuid)," +
                "'childReservations',(select jsonb_agg(to_jsonb(r) order by r.family,r.child_id) from child_id_reservations r where r.owner_id='" + ownerId + "'::uuid)," +
                "'credentials',(select jsonb_agg(jsonb_build_object('fingerprint',encode(c.fingerprint,'hex'),'expires',c.expires_at,'revoked',s.revoked_at) order by c.fingerprint) from auth_credentials c join auth_sessions s on s.id=c.session_id where s.account_id='" + ownerId + "'::uuid))::text";
        return jdbc.queryForObject(sql, String.class);
    }

    @Test
    void emptyAccountHasStableNonemptyCursorAndValidationDoesNotFallBackToInitial() {
        Account empty = account();
        Reply initial = pull(empty, null, null);
        assertPullEnvelope(initial);
        assertTrue(initial.json().path("batches").isEmpty());
        assertFalse(initial.json().path("hasMore").asBoolean());
        String start = initial.json().path("nextCursor").asText();
        assertEquals(start, pull(empty, null, 100).json().path("nextCursor").asText(), "absent and explicit default limits agree");
        Reply emptyTail = pull(empty, start, 1);
        assertPullEnvelope(emptyTail);
        assertTrue(emptyTail.json().path("batches").isEmpty());
        assertEquals(start, emptyTail.json().path("nextCursor").asText(), "empty pages preserve the supplied boundary");

        for (String invalid : List.of("", "not-a-token", "A".repeat(200))) {
            Reply bad = pull(empty, invalid, null);
            assertEquals(400, bad.status(), bad.text());
            assertEquals("invalid_cursor", bad.json().path("errorCode").asText());
        }
        for (String query : List.of("?unknown=1", "?cursor=x&cursor=y", "?limit=1&limit=2", "?limit=0", "?limit=201", "?limit=+1", "?limit=%201", "?limit=1.0", "?limit=%EF%BC%91%EF%BC%92")) {
            Reply bad = get(empty, "/v1/sync/pull" + query);
            assertEquals(400, bad.status(), query + ": " + bad.text());
            assertEquals("invalid_request", bad.json().path("errorCode").asText());
        }
        for (String query : List.of("?limit=1", "?limit=200", "?limit=100")) {
            Reply valid = get(empty, "/v1/sync/pull" + query);
            assertPullEnvelope(valid);
            assertTrue(valid.json().path("batches").isEmpty());
            assertEquals(start, valid.json().path("nextCursor").asText());
        }
    }

    @Test
    void pullsDirectAndSyncChangesAsCanonicalWholeBatchesAndKeepsHistoricalProjection() {
        Account owner = account();
        String custom = id();
        Reply createdExercise = send(owner, "POST", "/v1/exercises", "{\"id\":\"" + custom + "\",\"name\":\"Strength label v1\",\"category\":\"conditioning\",\"difficultyLevel\":\"advanced\",\"forceType\":\"pull\",\"mechanics\":\"compound\",\"primaryMuscleGroupId\":\"5181c3d4-3cfd-4e9e-9ae3-3de472e084bc\",\"secondaryMuscleGroupIds\":[\"cf81b51f-97d4-4cb2-ab3c-1d59472481ed\"],\"equipmentId\":\"22ad09f6-c689-4096-ad05-971ca8a6f0aa\",\"equipmentNumItems\":0,\"instructions\":[\"first\",\"second\"]}");
        assertEquals(201, createdExercise.status(), createdExercise.text());
        JsonNode exercise = createdExercise.json();
        assertEquals(1, exercise.path("revision").asLong());

        String routine = id(), routineEntry = id(), routineSet = id();
        assertEquals(201, send(owner, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status());
        Reply afterRoutineCreate = pull(owner, null, 100);
        assertPullEnvelope(afterRoutineCreate);
        assertEquals(2, afterRoutineCreate.json().path("batches").size());
        String priorRoutineCursor = afterRoutineCreate.json().path("nextCursor").asText();
        String routineInput = "{\"id\":\"" + routine + "\",\"name\":\"Draft notes\",\"isDraft\":true,\"exercises\":[{\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + routineSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        Reply draft = send(owner, "PUT", "/v1/routines/" + routine, routineInput);
        assertEquals(200, draft.status(), draft.text());

        String oldActive = id(), oldEntry = id(), oldSet = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + oldActive + "\",\"name\":\"old active\",\"startedAt\":\"2025-04-01T00:00:00Z\"}").status());
        String activeInput = "{\"id\":\"" + oldActive + "\",\"name\":\"Old session\",\"status\":\"active\",\"startedAt\":\"2025-04-01T00:00:00Z\",\"exercises\":[{\"id\":\"" + oldEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,\"notes\":\"old notes\",\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + oldSet + "\",\"position\":0,\"repetitions\":0,\"weightKg\":0,\"isCompleted\":true,\"restSeconds\":0}]}]}";
        assertEquals(200, send(owner, "PUT", "/v1/workouts/" + oldActive, activeInput).status());
        assertEquals(200, send(owner, "POST", "/v1/workouts/" + oldActive + "/finish", "{\"finishedAt\":\"2025-04-01T01:00:00Z\"}").status());
        String replacedActive = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + replacedActive + "\",\"name\":\"replace me\",\"startedAt\":\"2025-04-02T00:00:00Z\"}").status());
        String newActive = id();
        Reply replacement = send(owner, "POST", "/v1/workouts", "{\"id\":\"" + newActive + "\",\"name\":\"new active\",\"startedAt\":\"2025-04-02T00:00:00Z\"}");
        assertEquals(201, replacement.status(), replacement.text());
        JsonNode directBatch = directBatchAt(owner.id(), latestCursorNumber(owner.id()));
        assertEquals(2, directBatch.size(), "replacing an active workout publishes one complete two-change batch");
        assertEquals(replacedActive, directBatch.get(0).path("aggregate_id").asText());
        assertEquals("delete", directBatch.get(0).path("operation").asText());
        assertEquals(newActive, directBatch.get(1).path("aggregate_id").asText());
        assertEquals("upsert", directBatch.get(1).path("operation").asText());

        String fromPush = id();
        JsonNode pushed = push(owner, List.of(pushMutation(id(), fromPush, "Synced custom root")));
        assertEquals("accepted", pushed.path("results").get(0).path("status").asText());
        String syncCursor = pushed.path("results").get(0).path("batch").path("cursor").asText();
        assertFalse(syncCursor.isBlank(), "push must issue a nonempty opaque cursor");

        // Expected values below are specified from the request and accepted domain rules, not copied from a mapper response.
        Reply beforeRename = pull(owner, null, 200);
        assertPullEnvelope(beforeRename);
        JsonNode batches = beforeRename.json().path("batches");
        assertEquals(9, batches.size());
        assertBatchContains(batches, "customExercise", custom, "upsert", 1);
        JsonNode customRecord = findChange(batches, "customExercise", custom, "upsert", 1).path("record");
        assertEquals("conditioning", customRecord.path("category").asText());
        assertEquals("advanced", customRecord.path("difficultyLevel").asText());
        assertEquals("pull", customRecord.path("forceType").asText());
        assertEquals("compound", customRecord.path("mechanics").asText());
        assertEquals(List.of("first", "second"), JSON.convertValue(customRecord.path("instructions"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of("5181c3d4-3cfd-4e9e-9ae3-3de472e084bc", "cf81b51f-97d4-4cb2-ab3c-1d59472481ed"), customRecord.path("muscleGroups").findValuesAsText("id"));
        assertEquals(List.of("primary", "secondary"), customRecord.path("muscleGroups").findValuesAsText("type"));
        assertEquals("22ad09f6-c689-4096-ad05-971ca8a6f0aa", customRecord.path("equipment").get(0).path("id").asText());
        assertEquals(0, customRecord.path("equipment").get(0).path("numItems").asInt());
        assertBatchContains(batches, "routine", routine, "upsert", 2);
        JsonNode routineChange = findChange(batches, "routine", routine, "upsert", 2);
        JsonNode routineRecord = routineChange.path("record");
        assertEquals("Draft notes", routineRecord.path("name").asText());
        assertTrue(routineRecord.path("isDraft").asBoolean());
        assertEquals(routineEntry, routineRecord.path("exercises").get(0).path("id").asText());
        assertEquals("Strength label v1", routineRecord.path("exercises").get(0).path("exerciseName").asText());
        assertTrue(routineRecord.path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, routineRecord.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());

        assertBatchContains(batches, "workout", oldActive, "upsert", 2);
        assertBatchContains(batches, "workout", oldActive, "upsert", 3);
        JsonNode historical = findChange(batches, "workout", oldActive, "upsert", 3).path("record");
        assertBatchContains(batches, "workout", replacedActive, "delete", 2);
        assertEquals("finished", historical.path("status").asText());
        assertEquals("Old session", historical.path("name").asText());
        assertEquals(oldEntry, historical.path("exercises").get(0).path("id").asText());
        assertEquals("old notes", historical.path("exercises").get(0).path("notes").asText());
        assertEquals("Strength label v1", historical.path("exercises").get(0).path("exerciseName").asText());
        JsonNode historicalSet = historical.path("exercises").get(0).path("sets").get(0);
        assertEquals(oldSet, historicalSet.path("id").asText());
        assertEquals(0, historicalSet.path("repetitions").asInt());
        assertEquals(0, historicalSet.path("weightKg").asDouble());
        assertEquals(0, historicalSet.path("restSeconds").asInt());
        assertBatchContains(batches, "workout", replacedActive, "upsert", 1);
        assertBatchContains(batches, "workout", newActive, "upsert", 1);
        assertBatchContains(batches, "customExercise", fromPush, "upsert", 1);
        assertEquals(syncCursor, findBatchForAggregate(batches, "customExercise", fromPush).path("cursor").asText());

        Reply afterPushCursor = pull(owner, syncCursor, null);
        assertPullEnvelope(afterPushCursor);
        assertTrue(afterPushCursor.json().path("batches").isEmpty(), "push cursor continues from its committed boundary");
        assertEquals(syncCursor, afterPushCursor.json().path("nextCursor").asText());
        Reply afterPriorCursor = pull(owner, priorRoutineCursor, 100);
        assertPullEnvelope(afterPriorCursor);
        assertEquals(7, afterPriorCursor.json().path("batches").size());
        assertBatchContains(afterPriorCursor.json().path("batches"), "workout", oldActive, "upsert", 2);
        assertBatchContains(afterPriorCursor.json().path("batches"), "customExercise", fromPush, "upsert", 1);
        assertFalse(hasChange(afterPriorCursor.json().path("batches"), "routine", routine, "upsert", 1),
                "the checkpoint is after routine creation, so revision 1 is earlier history");

        String rename = "{\"id\":\"" + custom + "\",\"name\":\"Strength label v2\",\"category\":\"conditioning\",\"instructions\":[\"changed\"]}";
        assertEquals(200, send(owner, "PUT", "/v1/exercises/" + custom, rename).status());
        Reply replayHistory = pull(owner, priorRoutineCursor, 2);
        assertPullEnvelope(replayHistory);
        JsonNode storedHistorical = findChange(replayHistory.json().path("batches"), "routine", routine, "upsert", 2).path("record");
        assertEquals("Strength label v1", storedHistorical.path("exercises").get(0).path("exerciseName").asText(), "pull replays stored canonical labels instead of rematerializing from current roots");
        assertEquals("Strength label v2", get(owner, "/v1/routines/" + routine).json().path("exercises").get(0).path("exerciseName").asText());
        Reply replacedRoutine = send(owner, "PUT", "/v1/routines/" + routine,
                "{\"id\":\"" + routine + "\",\"name\":\"Replacement after history\",\"isDraft\":true,\"exercises\":[]}");
        assertEquals(200, replacedRoutine.status(), replacedRoutine.text());
        assertEquals(204, send(owner, "DELETE", "/v1/routines/" + routine, null).status());
        Reply laterHistory = pull(owner, priorRoutineCursor, 100);
        assertPullEnvelope(laterHistory);
        JsonNode preservedRevision = findChange(laterHistory.json().path("batches"), "routine", routine, "upsert", 2).path("record");
        assertEquals("Draft notes", preservedRevision.path("name").asText());
        assertEquals("Strength label v1", preservedRevision.path("exercises").get(0).path("exerciseName").asText());
        assertEquals("Replacement after history", findChange(laterHistory.json().path("batches"), "routine", routine, "upsert", 3).path("record").path("name").asText());
        JsonNode deletion = findChange(laterHistory.json().path("batches"), "routine", routine, "delete", 4);
        assertTrue(deletion.path("record").isMissingNode() || deletion.path("record").isNull());
    }

    private boolean hasChange(JsonNode batches, String type, String aggregateId, String operation, long revision) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals(type) && change.path("aggregateId").asText().equals(aggregateId)
                    && change.path("operation").asText().equals(operation) && change.path("revision").asLong() == revision) return true;
        return false;
    }

    private long latestCursorNumber(UUID accountId) {
        return jdbc.queryForObject("select max(cursor) from sync_change_batches where account_id=?", Long.class, accountId);
    }

    private JsonNode directBatchAt(UUID accountId, long cursor) {
        List<JsonNode> changes = jdbc.query("select aggregate_type,aggregate_id,operation,revision from sync_changes where account_id=? and cursor=? order by position",
                (rs, row) -> JSON.createObjectNode().put("aggregate_type", rs.getString(1)).put("aggregate_id", rs.getObject(2).toString()).put("operation", rs.getString(3)).put("revision", rs.getLong(4)), accountId, cursor);
        var array = JSON.createArrayNode();
        changes.forEach(array::add);
        return array;
    }

    private JsonNode findChange(JsonNode batches, String type, String aggregateId, String operation) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals(type) && change.path("aggregateId").asText().equals(aggregateId)
                    && change.path("operation").asText().equals(operation)) return change;
        throw new AssertionError("Missing " + type + "/" + aggregateId + "/" + operation + " from " + batches);
    }

    private JsonNode findChange(JsonNode batches, String type, String aggregateId, String operation, long revision) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals(type) && change.path("aggregateId").asText().equals(aggregateId)
                    && change.path("operation").asText().equals(operation) && change.path("revision").asLong() == revision) return change;
        throw new AssertionError("Missing " + type + "/" + aggregateId + "/" + operation + " revision " + revision + " from " + batches);
    }

    private JsonNode findBatchForAggregate(JsonNode batches, String type, String aggregateId) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals(type) && change.path("aggregateId").asText().equals(aggregateId)) return batch;
        throw new AssertionError("Missing batch for " + type + "/" + aggregateId);
    }

    private JsonNode findBatchForAggregate(JsonNode batches, String type, String aggregateId, long revision) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals(type) && change.path("aggregateId").asText().equals(aggregateId)
                    && change.path("revision").asLong() == revision) return batch;
        throw new AssertionError("Missing batch for " + type + "/" + aggregateId + " revision " + revision);
    }

    private void assertBatchContains(JsonNode batches, String type, String aggregateId, String operation, long revision) {
        JsonNode change = findChange(batches, type, aggregateId, operation, revision);
        assertTrue(change.path("cursor").isMissingNode() || !change.path("cursor").asText().isBlank());
        assertChangeEnvelope(change);
    }

    @Test
    void limitOneTraversalHasNoGapsOrDuplicatesAndPreservesBatchAtomicity() {
        Account owner = account();
        String oldActive = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + oldActive + "\",\"name\":\"one\",\"startedAt\":\"2025-01-01T00:00:00Z\"}").status());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String exercise = id();
            ids.add(exercise);
            assertEquals(201, send(owner, "POST", "/v1/exercises", "{\"id\":\"" + exercise + "\",\"name\":\"page " + i + "\"}").status());
        }
        String replacement = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + replacement + "\",\"name\":\"two\",\"startedAt\":\"2025-01-02T00:00:00Z\"}").status());
        String start = pull(owner, null, 1).json().path("batches").get(0).path("cursor").asText();
        List<String> batchIds = new ArrayList<>();
        List<String> aggregateIds = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        boolean more;
        do {
            Reply page = pull(owner, cursor, 1);
            assertPullEnvelope(page);
            JsonNode body = page.json();
            assertEquals(1, body.path("batches").size());
            JsonNode batch = body.path("batches").get(0);
            assertTrue(seenCursors.add(batch.path("cursor").asText()), "each page advances to a fresh committed boundary");
            batchIds.add(batch.path("batchId").asText());
            for (JsonNode change : batch.path("changes")) aggregateIds.add(change.path("aggregateId").asText());
            assertEquals(batch.path("cursor").asText(), body.path("nextCursor").asText());
            cursor = body.path("nextCursor").asText();
            more = body.path("hasMore").asBoolean();
            if (more) assertFalse(batch.path("changes").isEmpty());
        } while (more);

        assertEquals(6, batchIds.size(), "one workout start, four custom roots, and one two-change replacement are six batches");
        assertEquals(6, new HashSet<>(batchIds).size());
        assertEquals(oldActive, aggregateIds.get(0));
        assertTrue(aggregateIds.containsAll(ids));
        Reply terminal = pull(owner, cursor, 1);
        assertPullEnvelope(terminal);
        assertTrue(terminal.json().path("batches").isEmpty());
        assertFalse(terminal.json().path("hasMore").asBoolean());
        assertEquals(cursor, terminal.json().path("nextCursor").asText());

        Reply replacementPage = pull(owner, start, 200);
        assertPullEnvelope(replacementPage);
        JsonNode replacementBatch = findBatchForAggregate(replacementPage.json().path("batches"), "workout", replacement);
        assertEquals(2, replacementBatch.path("changes").size());
        assertEquals(oldActive, replacementBatch.path("changes").get(0).path("aggregateId").asText());
        assertEquals("delete", replacementBatch.path("changes").get(0).path("operation").asText());
        assertEquals(replacement, replacementBatch.path("changes").get(1).path("aggregateId").asText());
        assertEquals("upsert", replacementBatch.path("changes").get(1).path("operation").asText());
        assertFalse(replacementPage.json().path("hasMore").asBoolean());
    }

    @Test
    void moreThanTwoHundredBatchesUseExactTerminalFlagAndRespectBatchCountLimits() {
        Account owner = account();
        for (int base = 0; base < 201; base += 100) {
            List<String> mutations = new ArrayList<>();
            for (int i = base; i < Math.min(base + 100, 201); i++) mutations.add(pushMutation(id(), id(), "pull batch " + i));
            JsonNode response = push(owner, mutations);
            assertEquals(mutations.size(), response.path("results").size());
            for (JsonNode outcome : response.path("results")) assertEquals("accepted", outcome.path("status").asText());
        }
        Reply first = pull(owner, null, 200);
        assertPullEnvelope(first);
        assertEquals(200, first.json().path("batches").size());
        assertTrue(first.json().path("hasMore").asBoolean());
        String cursor = first.json().path("nextCursor").asText();
        Reply last = pull(owner, cursor, 200);
        assertPullEnvelope(last);
        assertEquals(1, last.json().path("batches").size());
        assertFalse(last.json().path("hasMore").asBoolean(), "exact/partial terminal is determined by existence of a next batch");
        assertNotEquals(cursor, last.json().path("nextCursor").asText());
        Reply defaultPage = pull(owner, null, null);
        assertEquals(100, defaultPage.json().path("batches").size());
        assertEquals(pull(owner, null, 100).json(), defaultPage.json());
    }

    @Test
    void cursorCanBeUsedByAnotherLiveSessionButIsAccountBound() {
        Account owner = account();
        Account otherSession = session(owner.id(), "1 hour", false, false);
        Account foreign = account();
        String root = id();
        assertEquals(201, send(owner, "POST", "/v1/exercises", "{\"id\":\"" + root + "\",\"name\":\"private pull item\"}").status());
        JsonNode page = pull(owner, null, 1).json();
        String cursor = page.path("nextCursor").asText();
        Reply sameAccount = pull(otherSession, cursor, 1);
        assertPullEnvelope(sameAccount);
        assertEquals(0, sameAccount.json().path("batches").size());
        Reply foreignCursor = pull(foreign, cursor, 1);
        assertEquals(400, foreignCursor.status(), foreignCursor.text());
        assertEquals("invalid_cursor", foreignCursor.json().path("errorCode").asText());
    }

    @Test
    void authorizationAndGetBodyValidationDoNotWriteProtectedStateOrReplayEvidence() {
        Account owner = account();
        String custom = id();
        assertEquals(201, send(owner, "POST", "/v1/exercises", "{\"id\":\"" + custom + "\",\"name\":\"unchanged\"}").status());
        Account expired = session(owner.id(), "-1 second", false, false);
        Account revoked = session(owner.id(), "1 hour", true, false);
        Account disabled = account();
        jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", disabled.id());
        String before = accountState(owner.id());

        for (Account invalid : List.of(expired, revoked, disabled)) {
            Reply result = pull(invalid, null, null);
            assertEquals(401, result.status(), result.text());
            assertEquals("authentication_required", result.json().path("errorCode").asText());
        }
        Reply noAuth = get(null, "/v1/sync/pull");
        assertEquals(401, noAuth.status());
        Reply badAuth = requestWithBearer("not-a-token", "GET", "/v1/sync/pull", null);
        assertEquals(401, badAuth.status());

        Reply emptyBody = request(owner, "GET", "/v1/sync/pull", "", UUID.randomUUID().toString());
        assertEquals(200, emptyBody.status(), emptyBody.text());
        Reply nonemptyBody = request(owner, "GET", "/v1/sync/pull", "{}", null);
        assertEquals(422, nonemptyBody.status(), nonemptyBody.text());
        assertEquals("validation_error", nonemptyBody.json().path("errorCode").asText());
        String readKey = UUID.randomUUID().toString();
        Reply keyed = request(owner, "GET", "/v1/sync/pull", null, readKey);
        assertEquals(200, keyed.status());
        assertEquals(0, jdbc.queryForObject("select count(*) from resource_mutations where owner_id=? and idempotency_key=?", Integer.class, owner.id(), UUID.fromString(readKey)));
        assertEquals(before, accountState(owner.id()), "successful/error reads may only register missing boundary tokens, not protected state or credentials");
    }

    private Reply requestWithBearer(String bearer, String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + bearer);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
