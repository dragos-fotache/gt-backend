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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncSnapshotHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "snapshot-integration-audience";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;

    private record Account(UUID id, String accessToken) {}
    private record Reply(int status, byte[] bytes) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        JsonNode json() {
            try { return JSON.readTree(bytes); }
            catch (Exception e) { throw new AssertionError("Expected JSON response: " + text(), e); }
        }
    }

    private Account account() { return session(UUID.randomUUID(), "1 hour", false); }

    private Account session(UUID accountId, String lifetime, boolean revoked) {
        if (jdbc.queryForObject("select count(*) from accounts where id=?", Integer.class, accountId) == 0)
            jdbc.update("insert into accounts(id) values(?)", accountId);
        UUID sessionId = UUID.randomUUID();
        String access = UUID.randomUUID().toString();
        jdbc.update("insert into auth_sessions(id,account_id,created_at,revoked_at) values(?,?,clock_timestamp(),?)",
                sessionId, accountId, revoked ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+(?::interval))",
                crypto.digest(access), sessionId, lifetime);
        return new Account(accountId, access);
    }

    private String id() { return UUID.randomUUID().toString(); }

    private Reply request(Account account, String method, String path, byte[] body) {
        return request(account, method, path, body, null);
    }

    private Reply request(Account account, String method, String path, byte[] body, String idempotencyKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (account != null) builder.header("Authorization", "Bearer " + account.accessToken());
            if (body != null) builder.header("Content-Type", "application/json");
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    private Reply get(Account account, String path) { return request(account, "GET", path, null); }
    private Reply getWithoutCredentials(String path) { return request(null, "GET", path, null); }
    private Reply send(Account account, String method, String path, String body) {
        return request(account, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID().toString());
    }

    private Reply snapshot(Account account, String token, Integer limit) {
        List<String> query = new ArrayList<>();
        if (token != null) query.add("pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        if (limit != null) query.add("limit=" + limit);
        return get(account, "/v1/sync/snapshot" + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    private void assertSnapshot(Reply reply) {
        assertEquals(200, reply.status(), reply.text());
        JsonNode body = reply.json();
        assertTrue(body.path("records").isArray());
        assertTrue(body.path("baselineCursor").isTextual() && !body.path("baselineCursor").asText().isBlank());
        assertTrue(body.path("hasMore").isBoolean());
        if (body.path("hasMore").asBoolean()) {
            assertFalse(body.path("records").isEmpty(), "hasMore requires nonempty record progress");
            assertTrue(body.path("nextPageToken").isTextual() && !body.path("nextPageToken").asText().isBlank());
        } else {
            assertTrue(body.path("nextPageToken").isMissingNode() || body.path("nextPageToken").isNull(),
                    "terminal page has no continuation");
        }
        for (JsonNode record : body.path("records")) assertUpsert(record);
    }

    private void assertUpsert(JsonNode change) {
        assertTrue(Set.of("customExercise", "routine", "workout").contains(change.path("aggregateType").asText()));
        assertTrue(change.path("aggregateId").isTextual());
        assertEquals("upsert", change.path("operation").asText());
        assertTrue(change.path("revision").asLong() > 0);
        assertEquals(change.path("aggregateId").asText(), change.path("record").path("id").asText());
        assertEquals(change.path("revision").asLong(), change.path("record").path("revision").asLong());
        assertFalse(change.path("record").has("ownerId"));
        assertFalse(change.path("record").has("owner_id"));
    }

    private Reply createCustom(Account owner, String customId, String name, String instructions) {
        String body = "{\"id\":\"" + customId + "\",\"name\":\"" + name + "\",\"category\":\"snapshot category\"," +
                "\"difficultyLevel\":\"advanced\",\"forceType\":\"pull\",\"mechanics\":\"compound\"," +
                "\"primaryMuscleGroupId\":\"5181c3d4-3cfd-4e9e-9ae3-3de472e084bc\"," +
                "\"secondaryMuscleGroupIds\":[\"cf81b51f-97d4-4cb2-ab3c-1d59472481ed\"]," +
                "\"equipmentId\":\"22ad09f6-c689-4096-ad05-971ca8a6f0aa\",\"equipmentNumItems\":0," +
                "\"instructions\":" + instructions + "}";
        return send(owner, "POST", "/v1/exercises", body);
    }

    private Reply createEmptyDraft(Account owner, String routineId) {
        return send(owner, "POST", "/v1/routines", "{\"id\":\"" + routineId + "\"}");
    }

    private Reply createDraft(Account owner, String routineId, String customId) {
        return createDraft(owner, routineId, customId, id(), id());
    }

    private Reply createDraft(Account owner, String routineId, String customId, String exerciseEntryId, String setId) {
        Reply created = send(owner, "POST", "/v1/routines", "{\"id\":\"" + routineId + "\"}");
        if (created.status() != 201) return created;
        String body = "{\"id\":\"" + routineId + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":[" +
                "{\"id\":\"" + exerciseEntryId + "\",\"exerciseId\":\"" + customId + "\",\"position\":0," +
                "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + setId + "\",\"position\":0," +
                "\"repetitions\":null,\"weightKg\":0}]}]}";
        return send(owner, "PUT", "/v1/routines/" + routineId, body);
    }

    private Reply createFinishedWorkout(Account owner, String workoutId, String customId) {
        return createFinishedWorkout(owner, workoutId, customId, id(), id());
    }

    private Reply createFinishedWorkout(Account owner, String workoutId, String customId, String exerciseEntryId, String setId) {
        Reply created = send(owner, "POST", "/v1/workouts", "{\"id\":\"" + workoutId + "\",\"name\":\"Frozen history\"," +
                "\"startedAt\":\"2025-01-02T03:04:05.000Z\"}");
        if (created.status() != 201) return created;
        String body = "{\"id\":\"" + workoutId + "\",\"name\":\"Frozen history\",\"status\":\"active\"," +
                "\"startedAt\":\"2025-01-02T03:04:05Z\",\"exercises\":[{" +
                "\"id\":\"" + exerciseEntryId + "\",\"exerciseId\":\"" + customId + "\",\"position\":0," +
                "\"notes\":\"finished notes\",\"restDurationSeconds\":90,\"sets\":[" +
                "{\"id\":\"" + setId + "\",\"position\":0,\"repetitions\":8,\"weightKg\":10.5," +
                "\"isCompleted\":true,\"restSeconds\":37}]}]}";
        Reply updated = send(owner, "PUT", "/v1/workouts/" + workoutId, body);
        if (updated.status() != 200) return updated;
        return send(owner, "POST", "/v1/workouts/" + workoutId + "/finish", "{\"finishedAt\":\"2025-01-02T04:05:06Z\"}");
    }

    private Reply createActiveWorkout(Account owner, String workoutId, String customId) {
        return createActiveWorkout(owner, workoutId, customId, id(), id());
    }

    private Reply createActiveWorkout(Account owner, String workoutId, String customId, String exerciseEntryId, String setId) {
        Reply created = send(owner, "POST", "/v1/workouts", "{\"id\":\"" + workoutId + "\",\"name\":\"Frozen workout\"," +
                "\"startedAt\":\"2025-02-03T04:05:06.123Z\"}");
        if (created.status() != 201) return created;
        String body = "{\"id\":\"" + workoutId + "\",\"name\":\"Frozen workout\",\"status\":\"active\"," +
                "\"startedAt\":\"2025-02-03T04:05:06.123Z\",\"exercises\":[{" +
                "\"id\":\"" + exerciseEntryId + "\",\"exerciseId\":\"" + customId + "\",\"position\":0," +
                "\"notes\":\"keep these notes\",\"restDurationSeconds\":0,\"sets\":[" +
                "{\"id\":\"" + setId + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0," +
                "\"isCompleted\":false,\"restSeconds\":null}]}]}";
        return send(owner, "PUT", "/v1/workouts/" + workoutId, body);
    }

    private void assertError(Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.text());
        assertEquals(code, reply.json().path("errorCode").asText());
    }

    @Test
    void capturesAllRootKindsWithIndependentCanonicalExpectationsAndTypeScopedIdentity() {
        Account owner = account();
        String sharedId = "80000000-0000-0000-0000-000000000001";
        String lowCustomId = "7fffffff-ffff-ffff-ffff-ffffffffffff";
        String highCustomId = "80000000-0000-0000-0000-000000000002";
        Reply customHigh = createCustom(owner, highCustomId, "Original custom", "[\"first\",\"second\"]");
        assertEquals(201, customHigh.status(), customHigh.text());
        Reply customLow = createCustom(owner, lowCustomId, "Canonical UUID edge", "null");
        assertEquals(201, customLow.status(), customLow.text());
        Reply sameUuidCustom = createCustom(owner, sharedId, "Shared root id", "[]");
        assertEquals(201, sameUuidCustom.status(), sameUuidCustom.text());
        String emptyDraftId = "71000000-0000-0000-0000-000000000001";
        Reply emptyDraft = createEmptyDraft(owner, emptyDraftId);
        assertEquals(201, emptyDraft.status(), emptyDraft.text());
        String savedRoutineId = "72000000-0000-0000-0000-000000000001";
        String savedExerciseEntryId = "74000000-0000-0000-0000-000000000001";
        String savedSetId = "74000000-0000-0000-0000-000000000002";
        Reply savedRoutine = createDraft(owner, savedRoutineId, sharedId, savedExerciseEntryId, savedSetId);
        assertEquals(200, savedRoutine.status(), savedRoutine.text());
        Reply saved = send(owner, "POST", "/v1/routines/" + savedRoutineId + "/save", "{\"name\":\"Saved snapshot routine\"}");
        assertEquals(200, saved.status(), saved.text());
        String emptySavedId = "73000000-0000-0000-0000-000000000001";
        assertEquals(200, createDraft(owner, emptySavedId, sharedId).status());
        assertEquals(200, send(owner, "POST", "/v1/routines/" + emptySavedId + "/save", "{\"name\":\"Temporary saved\"}").status());
        Reply emptySaved = send(owner, "PUT", "/v1/routines/" + emptySavedId,
                "{\"id\":\"" + emptySavedId + "\",\"name\":\"\",\"isDraft\":false,\"exercises\":[]}");
        assertEquals(200, emptySaved.status(), emptySaved.text());
        String draftExerciseEntryId = "75000000-0000-0000-0000-000000000001";
        String draftSetId = "75000000-0000-0000-0000-000000000002";
        Reply draft = createDraft(owner, sharedId, sharedId, draftExerciseEntryId, draftSetId);
        assertEquals(200, draft.status(), draft.text());
        String finishedExerciseEntryId = "76000000-0000-0000-0000-000000000001";
        String finishedSetId = "76000000-0000-0000-0000-000000000002";
        Reply finished = createFinishedWorkout(owner, sharedId, sharedId, finishedExerciseEntryId, finishedSetId);
        assertEquals(200, finished.status(), finished.text());
        String activeId = "90000000-0000-0000-0000-000000000001";
        String activeExerciseEntryId = "77000000-0000-0000-0000-000000000001";
        String activeSetId = "77000000-0000-0000-0000-000000000002";
        Reply workout = createActiveWorkout(owner, activeId, sharedId, activeExerciseEntryId, activeSetId);
        assertEquals(200, workout.status(), workout.text());

        Reply page = snapshot(owner, null, null);
        assertSnapshot(page);
        JsonNode records = page.json().path("records");
        assertEquals(9, records.size());
        assertEquals(List.of("customExercise", "customExercise", "customExercise", "routine", "routine", "routine", "routine", "workout", "workout"),
                JSON.convertValue(records.findValuesAsText("aggregateType"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of(lowCustomId, sharedId, highCustomId, emptyDraftId, savedRoutineId, emptySavedId, sharedId, sharedId, activeId), records.findValuesAsText("aggregateId"),
                "UUID order uses canonical string order across the signed-UUID edge; equal IDs in different types remain distinct");

        JsonNode lowCustom = records.get(0).path("record");
        assertEquals("Canonical UUID edge", lowCustom.path("name").asText());
        assertTrue(lowCustom.path("instructions").isNull(), "null instructions differ from an empty array");
        JsonNode highCustom = records.get(2).path("record");
        assertEquals("Original custom", highCustom.path("name").asText());
        assertEquals(List.of("first", "second"), JSON.convertValue(highCustom.path("instructions"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("advanced", highCustom.path("difficultyLevel").asText());
        assertEquals("pull", highCustom.path("forceType").asText());
        assertEquals("compound", highCustom.path("mechanics").asText());
        assertEquals(List.of("5181c3d4-3cfd-4e9e-9ae3-3de472e084bc", "cf81b51f-97d4-4cb2-ab3c-1d59472481ed"),
                highCustom.path("muscleGroups").findValuesAsText("id"));
        assertEquals(List.of("primary", "secondary"), highCustom.path("muscleGroups").findValuesAsText("type"));
        assertEquals("22ad09f6-c689-4096-ad05-971ca8a6f0aa", highCustom.path("equipment").get(0).path("id").asText());
        assertEquals(0, highCustom.path("equipment").get(0).path("numItems").asInt());
        assertEquals(1, highCustom.path("revision").asLong());

        JsonNode emptyRoutine = records.get(3).path("record");
        assertTrue(emptyRoutine.path("isDraft").asBoolean());
        assertEquals("", emptyRoutine.path("name").asText());
        assertTrue(emptyRoutine.path("exercises").isArray() && emptyRoutine.path("exercises").isEmpty());

        JsonNode savedRoutineRecord = records.get(4).path("record");
        assertFalse(savedRoutineRecord.path("isDraft").asBoolean());
        assertEquals("Saved snapshot routine", savedRoutineRecord.path("name").asText());
        JsonNode savedExercise = savedRoutineRecord.path("exercises").get(0);
        assertEquals(savedExerciseEntryId, savedExercise.path("id").asText());
        assertEquals(sharedId, savedExercise.path("exerciseId").asText());
        assertEquals("Shared root id", savedExercise.path("exerciseName").asText());
        assertEquals(savedSetId, savedExercise.path("sets").get(0).path("id").asText());

        JsonNode emptySavedRoutine = records.get(5).path("record");
        assertFalse(emptySavedRoutine.path("isDraft").asBoolean());
        assertEquals("", emptySavedRoutine.path("name").asText());
        assertTrue(emptySavedRoutine.path("exercises").isArray() && emptySavedRoutine.path("exercises").isEmpty(),
                "a legitimate blank saved autosave remains a live snapshot root");

        JsonNode routine = records.get(6).path("record");
        assertTrue(routine.path("isDraft").asBoolean());
        assertEquals("", routine.path("name").asText());
        JsonNode draftExercise = routine.path("exercises").get(0);
        assertEquals(draftExerciseEntryId, draftExercise.path("id").asText());
        assertEquals(sharedId, draftExercise.path("exerciseId").asText());
        assertEquals("Shared root id", draftExercise.path("exerciseName").asText());
        assertEquals(0, draftExercise.path("restDurationSeconds").asInt());
        assertEquals(draftSetId, draftExercise.path("sets").get(0).path("id").asText());
        assertTrue(draftExercise.path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, routine.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());

        JsonNode finishedRecord = records.get(7).path("record");
        assertEquals("finished", finishedRecord.path("status").asText());
        assertEquals("Frozen history", finishedRecord.path("name").asText());
        assertEquals("2025-01-02T04:05:06Z", finishedRecord.path("finishedAt").asText());
        JsonNode finishedExercise = finishedRecord.path("exercises").get(0);
        assertEquals(finishedExerciseEntryId, finishedExercise.path("id").asText());
        assertEquals(sharedId, finishedExercise.path("exerciseId").asText());
        assertEquals("Shared root id", finishedExercise.path("exerciseName").asText());
        assertEquals(finishedSetId, finishedExercise.path("sets").get(0).path("id").asText());
        assertEquals("finished notes", finishedExercise.path("notes").asText());
        assertEquals(90, finishedRecord.path("exercises").get(0).path("restDurationSeconds").asInt());
        assertEquals(8, finishedRecord.path("exercises").get(0).path("sets").get(0).path("repetitions").asInt());
        assertEquals(10.5, finishedRecord.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        assertTrue(finishedRecord.path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertEquals(37, finishedRecord.path("exercises").get(0).path("sets").get(0).path("restSeconds").asInt());

        JsonNode active = records.get(8).path("record");
        assertEquals("active", active.path("status").asText());
        assertEquals("2025-02-03T04:05:06.123Z", active.path("startedAt").asText());
        assertTrue(active.path("finishedAt").isNull());
        JsonNode activeExercise = active.path("exercises").get(0);
        assertEquals(activeExerciseEntryId, activeExercise.path("id").asText());
        assertEquals(sharedId, activeExercise.path("exerciseId").asText());
        assertEquals("Shared root id", activeExercise.path("exerciseName").asText());
        assertEquals(activeSetId, activeExercise.path("sets").get(0).path("id").asText());
        assertEquals("keep these notes", activeExercise.path("notes").asText());
        assertEquals(0, activeExercise.path("restDurationSeconds").asInt());
        assertTrue(activeExercise.path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, active.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        assertTrue(active.path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());
    }

    @Test
    void continuationReplayIsIdenticalAndFrozenWhileLaterWritesRemainPullDiscoverable() {
        Account owner = account();
        String customId = "10000000-0000-0000-0000-000000000001";
        String routineId = "20000000-0000-0000-0000-000000000001";
        String workoutId = "30000000-0000-0000-0000-000000000001";
        assertEquals(201, createCustom(owner, customId, "Before rename", "[\"frozen\"]").status());
        assertEquals(200, createDraft(owner, routineId, customId).status());
        assertEquals(200, createActiveWorkout(owner, workoutId, customId).status());

        Reply first = snapshot(owner, null, 1);
        assertSnapshot(first);
        assertEquals(1, first.json().path("records").size());
        String baseline = first.json().path("baselineCursor").asText();
        String continuation = first.json().path("nextPageToken").asText();
        assertTrue(first.json().path("hasMore").asBoolean());
        Reply replay = snapshot(owner, continuation, 1);
        assertSnapshot(replay);
        assertEquals(replay.json(), snapshot(owner, continuation, 1).json(),
                "a valid continuation is replayable without advancing or changing its next token");

        Reply renamed = send(owner, "PUT", "/v1/exercises/" + customId,
                "{\"id\":\"" + customId + "\",\"name\":\"After rename\",\"instructions\":[\"new\"]}");
        assertEquals(200, renamed.status(), renamed.text());
        String laterCustomId = "40000000-0000-0000-0000-000000000001";
        assertEquals(201, createCustom(owner, laterCustomId, "Created after capture", "[]").status());
        assertEquals(204, send(owner, "DELETE", "/v1/routines/" + routineId, null).status());

        String token = continuation;
        List<JsonNode> remaining = new ArrayList<>();
        while (true) {
            Reply page = snapshot(owner, token, 1);
            assertSnapshot(page);
            assertEquals(baseline, page.json().path("baselineCursor").asText());
            remaining.add(page.json().path("records").get(0));
            if (!page.json().path("hasMore").asBoolean()) break;
            token = page.json().path("nextPageToken").asText();
        }
        assertEquals(2, remaining.size());
        JsonNode frozenRoutine = remaining.get(0).path("record");
        assertEquals("routine", remaining.get(0).path("aggregateType").asText());
        assertEquals("Before rename", frozenRoutine.path("exercises").get(0).path("exerciseName").asText());
        JsonNode frozenWorkout = remaining.get(1).path("record");
        assertEquals("workout", remaining.get(1).path("aggregateType").asText());
        assertEquals("Before rename", frozenWorkout.path("exercises").get(0).path("exerciseName").asText());

        Reply incremental = get(owner, "/v1/sync/pull?cursor=" + URLEncoder.encode(baseline, StandardCharsets.UTF_8));
        assertEquals(200, incremental.status(), incremental.text());
        assertTrue(incremental.json().path("batches").size() >= 3,
                "rename, later creation and routine deletion after the baseline remain discoverable through pull");
    }

    @Test
    void emptySnapshotAndTokenLimitAndQueryValidationFailClosed() {
        Account owner = account();
        Reply empty = snapshot(owner, null, null);
        assertSnapshot(empty);
        assertTrue(empty.json().path("records").isEmpty());
        assertFalse(empty.json().path("hasMore").asBoolean());
        assertTrue(empty.json().path("baselineCursor").asText().length() > 1);
        assertEquals(empty.json(), get(owner, "/v1/sync/snapshot?limit=200").json(), "omitted and explicit default limits agree");
        assertEquals(empty.json(), get(owner, "/v1/sync/snapshot?limit=0200").json(), "numeric spellings share the default limit context");

        for (String query : List.of("?unknown=1", "?limit=0", "?limit=501", "?limit=-1",
                "?limit=%201", "?limit=1.0", "?limit=1e2", "?limit=%EF%BC%91", "?limit=",
                "?limit=1&limit=2", "?pageToken=x&pageToken=y")) {
            Reply invalid = get(owner, "/v1/sync/snapshot" + query);
            assertEquals(400, invalid.status(), query + ": " + invalid.text());
            assertEquals("invalid_request", invalid.json().path("errorCode").asText());
        }
        assertError(snapshot(owner, "", null), 400, "invalid_snapshot_token");
        assertError(snapshot(owner, "not-a-snapshot-token", null), 400, "invalid_snapshot_token");
        assertError(get(owner, "/v1/sync/snapshot?pageToken=" + "A".repeat(4_096)),
                400, "invalid_snapshot_token");
        Reply pullToken = get(owner, "/v1/sync/pull");
        assertEquals(200, pullToken.status(), pullToken.text());
        assertError(snapshot(owner, pullToken.json().path("nextCursor").asText(), null), 400, "invalid_snapshot_token");
        assertError(request(owner, "GET", "/v1/sync/snapshot", "{}".getBytes(StandardCharsets.UTF_8), null),
                422, "validation_error");

        String before = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, owner.id()).toString();
        assertEquals("0", before, "snapshot/read validation does not publish change batches");
    }

    @Test
    void largeAggregateIsOneRecordAndRetainsEveryOrderedChild() {
        Account owner = account();
        String customId = "55000000-0000-0000-0000-000000000001";
        assertEquals(201, createCustom(owner, customId, "Large aggregate reference", "[]").status());
        String workoutId = "55000000-0000-0000-0000-000000000002";
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + workoutId + "\",\"name\":\"Many sets\"," +
                "\"startedAt\":\"2025-03-04T05:06:07Z\"}").status());
        String exerciseEntryId = "55000000-0000-0000-0000-000000000003";
        List<String> expectedSetIds = new ArrayList<>();
        StringBuilder input = new StringBuilder("{\"id\":\"" + workoutId + "\",\"name\":\"Many sets\",\"status\":\"active\"," +
                "\"startedAt\":\"2025-03-04T05:06:07Z\",\"exercises\":[{\"id\":\"" + exerciseEntryId + "\"," +
                "\"exerciseId\":\"" + customId + "\",\"position\":0,\"notes\":\"large but intact\",\"restDurationSeconds\":0,\"sets\":[");
        for (int position = 0; position < 256; position++) {
            if (position > 0) input.append(',');
            String setId = String.format(java.util.Locale.ROOT, "56000000-0000-0000-0000-%012d", position + 1);
            expectedSetIds.add(setId);
            input.append("{\"id\":\"").append(setId).append("\",\"position\":").append(position)
                    .append(",\"repetitions\":null,\"weightKg\":0,\"isCompleted\":false,\"restSeconds\":null}");
        }
        input.append("]}]}");
        Reply updated = send(owner, "PUT", "/v1/workouts/" + workoutId, input.toString());
        assertEquals(200, updated.status(), updated.text());

        Reply firstPage = snapshot(owner, null, 1);
        assertSnapshot(firstPage);
        assertEquals(1, firstPage.json().path("records").size(),
                "limit counts complete root records, not child rows or response bytes");
        assertEquals("customExercise", firstPage.json().path("records").get(0).path("aggregateType").asText());
        assertTrue(firstPage.json().path("hasMore").asBoolean());
        String continuation = firstPage.json().path("nextPageToken").asText();
        assertFalse(continuation.isBlank());
        Reply workoutPage = snapshot(owner, continuation, 1);
        assertSnapshot(workoutPage);
        assertEquals(firstPage.json().path("baselineCursor"), workoutPage.json().path("baselineCursor"));
        assertEquals(1, workoutPage.json().path("records").size());
        assertFalse(workoutPage.json().path("hasMore").asBoolean());
        JsonNode workoutChange = workoutPage.json().path("records").get(0);
        assertEquals("workout", workoutChange.path("aggregateType").asText());
        assertEquals(workoutId, workoutChange.path("aggregateId").asText());
        JsonNode workout = workoutChange.path("record");
        assertEquals(workoutId, workout.path("id").asText());
        JsonNode exercise = workout.path("exercises").get(0);
        assertEquals(exerciseEntryId, exercise.path("id").asText());
        assertEquals(customId, exercise.path("exerciseId").asText());
        assertEquals("Large aggregate reference", exercise.path("exerciseName").asText());
        assertEquals("large but intact", exercise.path("notes").asText());
        assertEquals(0, exercise.path("restDurationSeconds").asInt());
        JsonNode sets = exercise.path("sets");
        assertEquals(256, sets.size());
        for (int position = 0; position < expectedSetIds.size(); position++) {
            assertEquals(expectedSetIds.get(position), sets.get(position).path("id").asText());
            assertEquals(position, sets.get(position).path("position").asInt());
            assertTrue(sets.get(position).path("repetitions").isNull());
            assertEquals(0, sets.get(position).path("weightKg").asDouble());
            assertFalse(sets.get(position).path("isCompleted").asBoolean());
            assertTrue(sets.get(position).path("restSeconds").isNull());
        }
    }

    @Test
    void snapshotRequiresLiveAuthorizationAndTokenIsAccountBoundButSessionIndependent() {
        Account owner = account();
        String customId = "50000000-0000-0000-0000-000000000001";
        assertEquals(201, createCustom(owner, customId, "Account-scoped", "[]").status());
        String secondRoot = "50000000-0000-0000-0000-000000000002";
        assertEquals(201, createCustom(owner, secondRoot, "Second account-scoped", "[]").status());
        String tombstoneId = "50000000-0000-0000-0000-000000000003";
        assertEquals(201, createCustom(owner, tombstoneId, "Deleted private root", "[]").status());
        assertEquals(204, send(owner, "DELETE", "/v1/exercises/" + tombstoneId, null).status());
        Account foreign = account();
        String foreignRootId = "51000000-0000-0000-0000-000000000001";
        Reply foreignRoot = createCustom(foreign, foreignRootId, "Foreign private root", "[]");
        assertEquals(201, foreignRoot.status(), foreignRoot.text());
        Reply first = snapshot(owner, null, 1);
        assertSnapshot(first);
        assertTrue(first.json().path("hasMore").asBoolean());
        String token = first.json().path("nextPageToken").asText();
        assertFalse(token.isBlank());

        Account anotherSession = session(owner.id(), "1 hour", false);
        Reply sameAccount = snapshot(anotherSession, token, 1);
        assertSnapshot(sameAccount);
        assertEquals(1, sameAccount.json().path("records").size());
        assertEquals(secondRoot, sameAccount.json().path("records").get(0).path("aggregateId").asText());
        assertNotEquals(foreignRootId, first.json().path("records").get(0).path("aggregateId").asText());
        assertNotEquals(foreignRootId, sameAccount.json().path("records").get(0).path("aggregateId").asText());
        assertEquals(first.json().path("baselineCursor"), sameAccount.json().path("baselineCursor"));
        assertFalse(sameAccount.json().path("hasMore").asBoolean());
        assertError(snapshot(anotherSession, token, 2), 400, "invalid_snapshot_token");
        String equivalentOne = get(anotherSession, "/v1/sync/snapshot?pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&limit=01").json().path("baselineCursor").asText();
        assertEquals(first.json().path("baselineCursor").asText(), equivalentOne, "equivalent decimal limit spelling keeps the numeric token context");
        assertError(get(anotherSession, "/v1/sync/snapshot?pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8)),
                400, "invalid_snapshot_token");

        assertError(snapshot(foreign, token, 1), 400, "invalid_snapshot_token");
        String tampered = token.substring(0, 10) + (token.charAt(10) == 'A' ? 'B' : 'A') + token.substring(11);
        assertError(snapshot(anotherSession, tampered, 1), 400, "invalid_snapshot_token");
        assertError(getWithoutCredentials("/v1/sync/snapshot"), 401, "authentication_required");
        assertError(snapshot(new Account(owner.id(), "not-a-live-credential"), token, 1),
                401, "authentication_required");
        Account expired = session(owner.id(), "-1 second", false);
        Account revoked = session(owner.id(), "1 hour", true);
        assertError(snapshot(expired, token, 1), 401, "authentication_required");
        assertError(snapshot(revoked, token, 1), 401, "authentication_required");
        jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", owner.id());
        assertError(snapshot(owner, token, 1), 401, "authentication_required");
    }

    @Test
    void nonemptyPagesRequireProgressAndNeverSplitCanonicalRecords() {
        Account owner = account();
        List<String> mutationObjects = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            String aggregateId = String.format(java.util.Locale.ROOT, "60000000-0000-0000-0000-%012d", i + 1);
            ids.add(aggregateId);
            mutationObjects.add("{\"mutationId\":\"" + id() + "\",\"type\":\"customExercise.upsert\"," +
                    "\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + aggregateId + "\"," +
                    "\"localGeneration\":1,\"payload\":{\"id\":\"" + aggregateId + "\",\"name\":\"bulk " + i + "\"}}");
            if (mutationObjects.size() == 100 || i == 500) {
                Reply pushed = send(owner, "POST", "/v1/sync/push", "{\"mutations\":[" + String.join(",", mutationObjects) + "]}");
                assertEquals(200, pushed.status(), pushed.text());
                assertEquals(mutationObjects.size(), pushed.json().path("results").size());
                for (JsonNode result : pushed.json().path("results")) assertEquals("accepted", result.path("status").asText());
                mutationObjects.clear();
            }
        }

        Reply first = snapshot(owner, null, 500);
        assertSnapshot(first);
        assertEquals(500, first.json().path("records").size());
        assertTrue(first.json().path("hasMore").asBoolean());
        String baseline = first.json().path("baselineCursor").asText();
        String next = first.json().path("nextPageToken").asText();
        Reply last = snapshot(owner, next, 500);
        assertSnapshot(last);
        assertEquals(1, last.json().path("records").size());
        assertFalse(last.json().path("hasMore").asBoolean());
        assertEquals(baseline, last.json().path("baselineCursor").asText());
        assertEquals(ids.get(500), last.json().path("records").get(0).path("aggregateId").asText());
        Set<String> observed = new HashSet<>();
        first.json().path("records").forEach(record -> assertTrue(observed.add(record.path("aggregateId").asText())));
        last.json().path("records").forEach(record -> assertTrue(observed.add(record.path("aggregateId").asText())));
        assertEquals(501, observed.size());
    }
}
