package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectResourceHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String encryptionKey = Base64.getEncoder().encodeToString(new byte[32]);
    static final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("auth.google-audience", () -> "integration-audience");
        registry.add("auth.encryption-key", () -> encryptionKey);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthCrypto crypto;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record Account(UUID id, String token) {
    }

    private record Reply(int status, String body, byte[] bytes) {
        JsonNode node() {
            try {
                return json.readTree(body);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private Account account() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", id);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", session, id);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '1 hour')", crypto.digest(token), session);
        return new Account(id, token);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private Reply request(Account account, String method, String route, UUID key, String body) {
        return requestWithKey(account, method, route, key.toString(), body);
    }

    private Reply requestWithKey(Account account, String method, String route, String key, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .header("Authorization", "Bearer " + account.token)
                    .header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.method(method, HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Reply send(Account account, String method, String route, String body) {
        return request(account, method, route, UUID.randomUUID(), body);
    }

    private Reply requestBytes(Account account, String method, String route, UUID key, byte[] body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .header("Authorization", "Bearer " + account.token)
                    .header("Idempotency-Key", key.toString())
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Reply requestAt(int targetPort, Account account, String method, String route, UUID key, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + route))
                    .header("Authorization", "Bearer " + account.token)
                    .header("Idempotency-Key", key.toString())
                    .header("Content-Type", "application/json");
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.method(method, HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String exerciseInput(String exerciseId, String name, String optionals) {
        return "{\"id\":\"" + exerciseId + "\",\"name\":\"" + name + "\"" + optionals + "}";
    }

    private String routineInput(String root, String name, boolean draft, String children) {
        return "{\"id\":\"" + root + "\",\"name\":\"" + name + "\",\"isDraft\":" + draft + ",\"exercises\":" + children + "}";
    }

    private String routineExercise(String entry, String exercise, int position, String sets) {
        return "{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":" + position + ",\"restDurationSeconds\":0,\"sets\":" + sets + "}";
    }

    private String routineSet(String set, int position, String repetitions, String weight) {
        return "{\"id\":\"" + set + "\",\"position\":" + position + ",\"repetitions\":" + repetitions + ",\"weightKg\":" + weight + "}";
    }

    private String workoutInput(String root, String name, String status, String startedAt, String children) {
        return "{\"id\":\"" + root + "\",\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"startedAt\":\"" + startedAt + "\",\"exercises\":" + children + "}";
    }

    private String workoutExercise(String entry, String exercise, int position, String notes, String rest, String sets) {
        return "{\"id\":\"" + entry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":" + position + ",\"notes\":\"" + notes + "\",\"restDurationSeconds\":" + rest + ",\"sets\":" + sets + "}";
    }

    private String workoutSet(String set, int position, String reps, String weight, boolean complete, String rest) {
        return "{\"id\":\"" + set + "\",\"position\":" + position + ",\"repetitions\":" + reps + ",\"weightKg\":" + weight + ",\"isCompleted\":" + complete + ",\"restSeconds\":" + rest + "}";
    }

    @Test
    void allFourteenMutableRoutesReturnCanonicalResourceShapesAndExpectedStatus() {
        Account account = account();
        String custom = id();
        Reply createdExercise = send(account, "POST", "/v1/exercises", exerciseInput(custom, "QA custom", ",\"instructions\":[]"));
        assertEquals(201, createdExercise.status, createdExercise.body);
        assertEquals(custom, createdExercise.node().path("id").asText());
        assertEquals("reps", createdExercise.node().path("type").asText());
        assertEquals(1, createdExercise.node().path("revision").asLong());
        assertFalse(createdExercise.node().has("changes"));

        Reply updatedExercise = send(account, "PUT", "/v1/exercises/" + custom, exerciseInput(custom, "QA renamed", ",\"instructions\":null"));
        assertEquals(200, updatedExercise.status, updatedExercise.body);
        assertEquals("QA renamed", updatedExercise.node().path("name").asText());
        assertTrue(updatedExercise.node().path("instructions").isNull());
        assertEquals(2, updatedExercise.node().path("revision").asLong());

        String routine = id(), routineEntry = id(), routineSet = id();
        Reply draft = send(account, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}");
        assertEquals(201, draft.status, draft.body);
        assertEquals("", draft.node().path("name").asText());
        assertTrue(draft.node().path("isDraft").asBoolean());
        assertEquals(1, draft.node().path("revision").asLong());
        assertEquals(0, draft.node().path("exercises").size());
        UUID invalidSaveKey = UUID.randomUUID();
        Reply invalidSave = request(account, "POST", "/v1/routines/" + routine + "/save", invalidSaveKey, "{\"name\":\"   \"}");
        assertEquals(422, invalidSave.status, invalidSave.body);
        assertEquals("validation_error", invalidSave.node().path("errorCode").asText());
        String routineChildren = "[" + routineExercise(routineEntry, custom, 0, "[" + routineSet(routineSet, 0, "null", "0") + "]") + "]";
        Reply updatedRoutine = send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "", true, routineChildren));
        assertEquals(200, updatedRoutine.status, updatedRoutine.body);
        assertEquals("", updatedRoutine.node().path("name").asText());
        assertEquals(2, updatedRoutine.node().path("revision").asLong());
        assertTrue(updatedRoutine.node().path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        Reply saved = send(account, "POST", "/v1/routines/" + routine + "/save", "{\"name\":\"QA saved\"}");
        assertEquals(200, saved.status, saved.body);
        assertFalse(saved.node().path("isDraft").asBoolean());
        assertEquals(3, saved.node().path("revision").asLong());
        Reply invalidSaveReplay = request(account, "POST", "/v1/routines/" + routine + "/save", invalidSaveKey, "{\"name\":\"   \"}");
        assertEquals(422, invalidSaveReplay.status, invalidSaveReplay.body);
        assertEquals(invalidSave.body, invalidSaveReplay.body, "valid-shape domain validation_error remains original after state changes");
        Reply emptySavedAutosave = send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "", false, "[]"));
        assertEquals(200, emptySavedAutosave.status, emptySavedAutosave.body);
        assertFalse(emptySavedAutosave.node().path("isDraft").asBoolean());
        assertEquals("", emptySavedAutosave.node().path("name").asText());
        assertEquals(0, emptySavedAutosave.node().path("exercises").size());
        Reply restoredSavedRoutine = send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "QA saved", false, routineChildren));
        assertEquals(200, restoredSavedRoutine.status, restoredSavedRoutine.body);
        assertFalse(restoredSavedRoutine.node().path("isDraft").asBoolean());

        String startedWorkout = id(), startedEntry = id(), startedSet = id();
        String routineBinding = "[{\"sourceExerciseId\":\"" + routineEntry + "\",\"id\":\"" + startedEntry + "\",\"sets\":[{\"sourceSetId\":\"" + routineSet + "\",\"id\":\"" + startedSet + "\"}]}]";
        Reply fromRoutine = send(account, "POST", "/v1/routines/" + routine + "/start-workout", "{\"id\":\"" + startedWorkout + "\",\"startedAt\":\"2025-03-01T00:00:00Z\",\"childIds\":" + routineBinding + "}");
        assertEquals(201, fromRoutine.status, fromRoutine.body);
        assertEquals("active", fromRoutine.node().path("status").asText());
        assertEquals(1, fromRoutine.node().path("revision").asLong());
        assertEquals("QA renamed", fromRoutine.node().path("exercises").get(0).path("exerciseName").asText());
        assertFalse(fromRoutine.node().path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertTrue(fromRoutine.node().path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());
        assertTrue(fromRoutine.node().path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, fromRoutine.node().path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());

        String workoutChildren = "[" + workoutExercise(startedEntry, custom, 0, "domain note", "0", "[" + workoutSet(startedSet, 0, "0", "0", true, "0") + "]") + "]";
        String workoutRecord = workoutInput(startedWorkout, "QA session", "active", "2025-03-01T00:00:00Z", workoutChildren);
        Reply updatedWorkout = send(account, "PUT", "/v1/workouts/" + startedWorkout, workoutRecord);
        assertEquals(200, updatedWorkout.status, updatedWorkout.body);
        assertEquals(0, updatedWorkout.node().path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        Reply finished = send(account, "POST", "/v1/workouts/" + startedWorkout + "/finish", "{\"finishedAt\":\"2025-03-01T01:00:00Z\"}");
        assertEquals(200, finished.status, finished.body);
        assertEquals("finished", finished.node().path("status").asText());
        Reply immutableHistory = send(account, "PUT", "/v1/workouts/" + startedWorkout, workoutRecord);
        assertEquals(409, immutableHistory.status, immutableHistory.body);
        assertEquals("invalid_state", immutableHistory.node().path("errorCode").asText());
        assertEquals(3, jdbc.queryForObject("select revision from workouts where id=?", Integer.class, UUID.fromString(startedWorkout)));

        String again = id(), againEntry = id(), againSet = id();
        String againBinding = "[{\"sourceExerciseId\":\"" + startedEntry + "\",\"id\":\"" + againEntry + "\",\"sets\":[{\"sourceSetId\":\"" + startedSet + "\",\"id\":\"" + againSet + "\"}]}]";
        Reply performedAgain = send(account, "POST", "/v1/workouts/" + startedWorkout + "/perform-again", "{\"id\":\"" + again + "\",\"startedAt\":\"2025-03-02T00:00:00Z\",\"childIds\":" + againBinding + "}");
        assertEquals(201, performedAgain.status, performedAgain.body);
        assertEquals("QA session", performedAgain.node().path("name").asText());
        assertEquals("domain note", performedAgain.node().path("exercises").get(0).path("notes").asText());
        assertFalse(performedAgain.node().path("exercises").get(0).path("sets").get(0).path("isCompleted").asBoolean());
        assertTrue(performedAgain.node().path("exercises").get(0).path("sets").get(0).path("restSeconds").isNull());

        String savedCopy = id(), savedCopyEntry = id(), savedCopySet = id();
        String saveBinding = "[{\"sourceExerciseId\":\"" + startedEntry + "\",\"id\":\"" + savedCopyEntry + "\",\"sets\":[{\"sourceSetId\":\"" + startedSet + "\",\"id\":\"" + savedCopySet + "\"}]}]";
        Reply savedAsRoutine = send(account, "POST", "/v1/workouts/" + startedWorkout + "/save-as-routine", "{\"id\":\"" + savedCopy + "\",\"name\":\"Copied\",\"childIds\":" + saveBinding + "}");
        assertEquals(201, savedAsRoutine.status, savedAsRoutine.body);
        assertFalse(savedAsRoutine.node().path("isDraft").asBoolean());
        assertFalse(savedAsRoutine.node().path("exercises").get(0).path("sets").get(0).has("isCompleted"));
        assertEquals(0, savedAsRoutine.node().path("exercises").get(0).path("sets").get(0).path("repetitions").asInt());
        assertEquals(0, savedAsRoutine.node().path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());

        String newWorkout = id();
        Reply startedEmpty = send(account, "POST", "/v1/workouts", "{\"id\":\"" + newWorkout + "\",\"name\":\"Empty\",\"startedAt\":\"2025-03-03T00:00:00Z\"}");
        assertEquals(201, startedEmpty.status, startedEmpty.body);
        assertEquals(13, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id),
                "thirteen accepted direct commands have committed once through this point");
        var startBatch = latestChanges(account.id);
        assertEquals(2, startBatch.size());
        assertEquals(again, startBatch.get(0).path("aggregate_id").asText());
        assertEquals("delete", startBatch.get(0).path("operation").asText());
        assertEquals(newWorkout, startBatch.get(1).path("aggregate_id").asText());
        assertEquals("upsert", startBatch.get(1).path("operation").asText());
        Reply editedEmpty = send(account, "PUT", "/v1/workouts/" + newWorkout, workoutInput(newWorkout, "Edited empty", "active", "2025-03-03T00:00:00Z", "[]"));
        assertEquals(200, editedEmpty.status, editedEmpty.body);
        assertEquals("Edited empty", editedEmpty.node().path("name").asText());
        Reply deletedWorkout = send(account, "DELETE", "/v1/workouts/" + newWorkout, null);
        assertEquals(204, deletedWorkout.status, deletedWorkout.body);
        assertEquals(0, deletedWorkout.bytes.length, "DELETE success is exactly bodyless");
        UUID inUseKey = UUID.randomUUID();
        Reply inUse = request(account, "DELETE", "/v1/exercises/" + custom, inUseKey, null);
        assertEquals(409, inUse.status, inUse.body);
        assertEquals("exercise_in_use", inUse.node().path("errorCode").asText());

        assertEquals(204, send(account, "DELETE", "/v1/workouts/" + startedWorkout, null).status);
        assertEquals(204, send(account, "DELETE", "/v1/routines/" + routine, null).status);
        assertEquals(204, send(account, "DELETE", "/v1/routines/" + savedCopy, null).status);
        Reply inUseReplay = request(account, "DELETE", "/v1/exercises/" + custom, inUseKey, null);
        assertEquals(409, inUseReplay.status, inUseReplay.body);
        assertEquals(inUse.body, inUseReplay.body, "a durable domain rejection must replay after references are removed");
        Reply deletedExercise = send(account, "DELETE", "/v1/exercises/" + custom, null);
        assertEquals(204, deletedExercise.status, deletedExercise.body);
        assertEquals(0, deletedExercise.bytes.length);
        assertEquals(19, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id),
                "the fourteen route positives plus two saved-routine replacement autosaves each commit once; the workout start replacement has two ordered changes in its single batch");
    }

    private JsonNode latestChanges(UUID account) {
        Long cursor = jdbc.queryForObject("select max(cursor) from sync_change_batches where account_id=?", Long.class, account);
        var changes = json.createArrayNode();
        jdbc.query("select aggregate_type,aggregate_id,operation,revision,payload::text from sync_changes where account_id=? and cursor=? order by position", rs -> {
            var change = json.createObjectNode();
            change.put("aggregate_type", rs.getString("aggregate_type"));
            change.put("aggregate_id", rs.getObject("aggregate_id").toString());
            change.put("operation", rs.getString("operation"));
            change.put("revision", rs.getLong("revision"));
            String payload = rs.getString("payload");
            if (payload == null) change.putNull("payload");
            else try {
                change.set("payload", json.readTree(payload));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            changes.add(change);
        }, account, cursor);
        return changes;
    }

    @Test
    void directPathPayloadBindingOwnershipAndBundledReadOnlyRulesAreEnforced() {
        Account owner = account(), other = account();
        String custom = id();
        assertEquals(201, send(owner, "POST", "/v1/exercises", exerciseInput(custom, "Owned", "")).status);
        Reply createCollision = send(owner, "POST", "/v1/exercises", exerciseInput(custom, "Do not overwrite", ""));
        assertEquals(409, createCollision.status, createCollision.body);
        assertEquals("id_collision", createCollision.node().path("errorCode").asText());
        String mismatch = id();
        Reply exerciseMismatch = send(owner, "PUT", "/v1/exercises/" + custom, exerciseInput(mismatch, "Must not replace", ""));
        assertEquals(422, exerciseMismatch.status, exerciseMismatch.body);
        assertEquals("validation_error", exerciseMismatch.node().path("errorCode").asText());
        assertEquals("Owned", jdbc.queryForObject("select name from exercises where id=?", String.class, UUID.fromString(custom)));
        Reply missingExercise = send(owner, "PUT", "/v1/exercises/" + id(), exerciseInput(id(), "Missing", ""));
        assertEquals(422, missingExercise.status, missingExercise.body);
        String missingRoot = id();
        Reply missingPut = send(owner, "PUT", "/v1/exercises/" + missingRoot, exerciseInput(missingRoot, "Missing", ""));
        assertEquals(404, missingPut.status, missingPut.body);
        assertEquals("not_found", missingPut.node().path("errorCode").asText());

        String foreignExercise = id();
        assertEquals(201, send(other, "POST", "/v1/exercises", exerciseInput(foreignExercise, "Private", "")).status);
        Reply foreignPut = send(owner, "PUT", "/v1/exercises/" + foreignExercise, exerciseInput(foreignExercise, "Probe", ""));
        assertEquals(404, foreignPut.status, foreignPut.body);
        assertEquals("not_found", foreignPut.node().path("errorCode").asText());
        Reply foreignDelete = send(owner, "DELETE", "/v1/exercises/" + foreignExercise, null);
        assertEquals(404, foreignDelete.status, foreignDelete.body);
        assertFalse(foreignDelete.body.contains(other.id.toString()));

        UUID bundled = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class);
        Reply bundledPut = send(owner, "PUT", "/v1/exercises/" + bundled, exerciseInput(bundled.toString(), "forbidden", ""));
        assertEquals(403, bundledPut.status, bundledPut.body);
        assertEquals("read_only", bundledPut.node().path("errorCode").asText());
        Reply bundledDelete = send(owner, "DELETE", "/v1/exercises/" + bundled, null);
        assertEquals(403, bundledDelete.status, bundledDelete.body);
        assertEquals("read_only", bundledDelete.node().path("errorCode").asText());

        String workout = id(), otherWorkout = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + workout + "\",\"name\":\"owner\",\"startedAt\":\"2025-06-01T00:00:00Z\"}").status);
        Reply workoutMismatch = send(owner, "PUT", "/v1/workouts/" + workout, workoutInput(otherWorkout, "mismatch", "active", "2025-06-01T00:00:00Z", "[]"));
        assertEquals(422, workoutMismatch.status, workoutMismatch.body);
        assertEquals("owner", jdbc.queryForObject("select name from workouts where id=?", String.class, UUID.fromString(workout)));
        String routine = id();
        assertEquals(201, send(owner, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status);
        Reply routineMismatch = send(owner, "PUT", "/v1/routines/" + routine, routineInput(id(), "mismatch", true, "[]"));
        assertEquals(422, routineMismatch.status, routineMismatch.body);
        assertEquals("", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(routine)));
    }

    @Test
    void routinePutIsExistingOnlyAndCannotPromoteOrDemoteAndCatalogueCreateCollides() {
        Account account = account();
        String missing = id();
        Reply missingPut = send(account, "PUT", "/v1/routines/" + missing, routineInput(missing, "missing", true, "[]"));
        assertEquals(404, missingPut.status, missingPut.body);
        assertEquals("not_found", missingPut.node().path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(missing)));

        UUID bundled = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class);
        String routine = id(), entry = id();
        assertEquals(201, send(account, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status);
        String eligibleChildren = "[" + routineExercise(entry, bundled.toString(), 0, "[]") + "]";
        int batchesBeforePromotion = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply promotion = send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "promote through PUT", false, eligibleChildren));
        assertEquals(409, promotion.status, promotion.body);
        assertEquals("invalid_state", promotion.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and is_draft and name='' and revision=1", Integer.class, UUID.fromString(routine)));
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_exercises where routine_id=?", Integer.class, UUID.fromString(routine)));
        assertEquals(batchesBeforePromotion, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        assertEquals(200, send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "eligible", true, eligibleChildren)).status);
        assertEquals(200, send(account, "POST", "/v1/routines/" + routine + "/save", "{\"name\":\"eligible\"}").status);
        int batchesBeforeDemotion = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply demotion = send(account, "PUT", "/v1/routines/" + routine, routineInput(routine, "eligible", true, eligibleChildren));
        assertEquals(409, demotion.status, demotion.body);
        assertEquals("invalid_state", demotion.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and not is_draft and name='eligible' and revision=3", Integer.class, UUID.fromString(routine)));
        assertEquals(batchesBeforeDemotion, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        String catalogName = jdbc.queryForObject("select name from exercises where id=?", String.class, bundled);
        Reply catalogCreate = send(account, "POST", "/v1/exercises", exerciseInput(bundled.toString(), "claim catalogue", ""));
        assertEquals(409, catalogCreate.status, catalogCreate.body);
        assertEquals("id_collision", catalogCreate.node().path("errorCode").asText());
        assertEquals(catalogName, jdbc.queryForObject("select name from exercises where id=? and owner_id is null", String.class, bundled));
    }

    @Test
    void directCopySourcesComeOnlyFromPathsAndRepresentativeSourceGuardsMapToHttpErrors() {
        Account owner = account(), foreign = account();
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();

        String savedRoutine = id(), routineEntry = id(), routineSet = id();
        assertEquals(201, send(owner, "POST", "/v1/routines", "{\"id\":\"" + savedRoutine + "\"}").status);
        String routineChildren = "[" + routineExercise(routineEntry, exercise, 0, "[" + routineSet(routineSet, 0, "null", "0") + "]") + "]";
        assertEquals(200, send(owner, "PUT", "/v1/routines/" + savedRoutine, routineInput(savedRoutine, "saved source", true, routineChildren)).status);
        assertEquals(200, send(owner, "POST", "/v1/routines/" + savedRoutine + "/save", "{\"name\":\"saved source\"}").status);

        String foreignRoutine = id(), foreignEntry = id(), foreignSet = id();
        assertEquals(201, send(foreign, "POST", "/v1/routines", "{\"id\":\"" + foreignRoutine + "\"}").status);
        String foreignChildren = "[" + routineExercise(foreignEntry, exercise, 0, "[" + routineSet(foreignSet, 0, "1", "1") + "]") + "]";
        assertEquals(200, send(foreign, "PUT", "/v1/routines/" + foreignRoutine, routineInput(foreignRoutine, "foreign source", true, foreignChildren)).status);
        assertEquals(200, send(foreign, "POST", "/v1/routines/" + foreignRoutine + "/save", "{\"name\":\"foreign source\"}").status);

        String draft = id(), draftEntry = id();
        assertEquals(201, send(owner, "POST", "/v1/routines", "{\"id\":\"" + draft + "\"}").status);
        String draftChildren = "[" + routineExercise(draftEntry, exercise, 0, "[]") + "]";
        assertEquals(200, send(owner, "PUT", "/v1/routines/" + draft, routineInput(draft, "draft source", true, draftChildren)).status);
        String draftTarget = id(), draftTargetEntry = id();
        String draftBinding = "[{\"sourceExerciseId\":\"" + draftEntry + "\",\"id\":\"" + draftTargetEntry + "\",\"sets\":[]}]";
        Reply draftStart = send(owner, "POST", "/v1/routines/" + draft + "/start-workout", "{\"id\":\"" + draftTarget + "\",\"startedAt\":\"2025-06-01T00:00:00Z\",\"childIds\":" + draftBinding + "}");
        assertEquals(409, draftStart.status, draftStart.body);
        assertEquals("invalid_state", draftStart.node().path("errorCode").asText());

        String activeSource = id(), activeEntry = id(), activeSet = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + activeSource + "\",\"name\":\"active source\",\"startedAt\":\"2025-06-01T00:00:00Z\"}").status);
        String activeChildren = "[" + workoutExercise(activeEntry, exercise, 0, "", "30", "[" + workoutSet(activeSet, 0, "1", "1", false, "null") + "]") + "]";
        assertEquals(200, send(owner, "PUT", "/v1/workouts/" + activeSource, workoutInput(activeSource, "active source", "active", "2025-06-01T00:00:00Z", activeChildren)).status);
        String activeTarget = id(), activeTargetEntry = id(), activeTargetSet = id();
        String activeBinding = "[{\"sourceExerciseId\":\"" + activeEntry + "\",\"id\":\"" + activeTargetEntry + "\",\"sets\":[{\"sourceSetId\":\"" + activeSet + "\",\"id\":\"" + activeTargetSet + "\"}]}]";
        Reply activeCopy = send(owner, "POST", "/v1/workouts/" + activeSource + "/perform-again", "{\"id\":\"" + activeTarget + "\",\"startedAt\":\"2025-06-02T00:00:00Z\",\"childIds\":" + activeBinding + "}");
        assertEquals(409, activeCopy.status, activeCopy.body);
        assertEquals("invalid_state", activeCopy.node().path("errorCode").asText());
        assertEquals("active", jdbc.queryForObject("select status from workouts where id=? and deleted_at is null", String.class, UUID.fromString(activeSource)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(activeTarget)));

        String finishedChildren = "[" + workoutExercise(activeEntry, exercise, 0, "source note", "75", "[" + workoutSet(activeSet, 0, "8", "42.5", true, "17") + "]") + "]";
        assertEquals(200, send(owner, "PUT", "/v1/workouts/" + activeSource, workoutInput(activeSource, "finished source", "active", "2025-06-01T00:00:00Z", finishedChildren)).status);
        assertEquals(200, send(owner, "POST", "/v1/workouts/" + activeSource + "/finish", "{\"finishedAt\":\"2025-06-01T01:00:00Z\"}").status);

        String missing = id(), missingTarget = id();
        Reply absentSource = send(owner, "POST", "/v1/workouts/" + missing + "/save-as-routine", "{\"id\":\"" + missingTarget + "\",\"name\":\"Missing\",\"childIds\":[]}");
        assertEquals(404, absentSource.status, absentSource.body);
        assertEquals("not_found", absentSource.node().path("errorCode").asText());
        String foreignTarget = id();
        String foreignBinding = "[{\"sourceExerciseId\":\"" + foreignEntry + "\",\"id\":\"" + id() + "\",\"sets\":[{\"sourceSetId\":\"" + foreignSet + "\",\"id\":\"" + id() + "\"}]}]";
        Reply foreignSource = send(owner, "POST", "/v1/routines/" + foreignRoutine + "/start-workout", "{\"id\":\"" + foreignTarget + "\",\"startedAt\":\"2025-06-02T00:00:00Z\",\"childIds\":" + foreignBinding + "}");
        assertEquals(404, foreignSource.status, foreignSource.body);
        assertEquals("not_found", foreignSource.node().path("errorCode").asText());
        assertFalse(foreignSource.body.contains(foreignRoutine));

        String shapeTarget = id();
        Reply shapeMismatch = send(owner, "POST", "/v1/workouts/" + activeSource + "/perform-again", "{\"id\":\"" + shapeTarget + "\",\"startedAt\":\"2025-06-02T00:00:00Z\",\"childIds\":[]}");
        assertEquals(409, shapeMismatch.status, shapeMismatch.body);
        assertEquals("source_shape_changed", shapeMismatch.node().path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(shapeTarget)));

        String routineCopy = id(), routineCopyEntry = id(), routineCopySet = id();
        String previousActive = id();
        assertEquals(201, send(owner, "POST", "/v1/workouts", "{\"id\":\"" + previousActive + "\",\"name\":\"previous active\",\"startedAt\":\"2025-06-02T00:00:00Z\"}").status);
        long savedRoutineRevision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(savedRoutine));
        String validStartBody = "{\"id\":\"" + routineCopy + "\",\"startedAt\":\"2025-06-03T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + routineEntry + "\",\"id\":\"" + routineCopyEntry + "\",\"sets\":[{\"sourceSetId\":\"" + routineSet + "\",\"id\":\"" + routineCopySet + "\"}]}]}";
        UUID startKey = UUID.randomUUID();
        Reply bodySourceStart = request(owner, "POST", "/v1/routines/" + savedRoutine + "/start-workout", startKey, validStartBody.substring(0, validStartBody.length() - 1) + ",\"sourceRoutineId\":\"" + savedRoutine + "\"}");
        assertEquals(422, bodySourceStart.status, bodySourceStart.body);
        assertEquals("validation_error", bodySourceStart.node().path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(routineCopy)));
        Reply pathSourceStart = request(owner, "POST", "/v1/routines/" + savedRoutine + "/start-workout", startKey, validStartBody);
        assertEquals(201, pathSourceStart.status, pathSourceStart.body);
        assertEquals(savedRoutineRevision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(savedRoutine)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and not is_draft and name='saved source'", Integer.class, UUID.fromString(savedRoutine)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_exercises where id=? and routine_id=?", Integer.class, UUID.fromString(routineEntry), UUID.fromString(savedRoutine)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_sets where id=? and repetitions is null and weight_kg=0", Integer.class, UUID.fromString(routineSet)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is not null", Integer.class, UUID.fromString(previousActive)));
        assertEquals(2, latestChanges(owner.id).size());
        assertEquals(previousActive, latestChanges(owner.id).get(0).path("aggregate_id").asText());
        assertEquals("delete", latestChanges(owner.id).get(0).path("operation").asText());
        assertEquals(routineCopy, latestChanges(owner.id).get(1).path("aggregate_id").asText());
        assertEquals("upsert", latestChanges(owner.id).get(1).path("operation").asText());

        String workoutCopy = id(), workoutCopyEntry = id(), workoutCopySet = id();
        String validPerformBody = "{\"id\":\"" + workoutCopy + "\",\"startedAt\":\"2025-06-04T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + activeEntry + "\",\"id\":\"" + workoutCopyEntry + "\",\"sets\":[{\"sourceSetId\":\"" + activeSet + "\",\"id\":\"" + workoutCopySet + "\"}]}]}";
        long finishedSourceRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(activeSource));
        UUID performKey = UUID.randomUUID();
        Reply bodySourcePerform = request(owner, "POST", "/v1/workouts/" + activeSource + "/perform-again", performKey, validPerformBody.substring(0, validPerformBody.length() - 1) + ",\"sourceWorkoutId\":\"" + activeSource + "\"}");
        assertEquals(422, bodySourcePerform.status, bodySourcePerform.body);
        assertEquals("validation_error", bodySourcePerform.node().path("errorCode").asText());
        Reply pathSourcePerform = request(owner, "POST", "/v1/workouts/" + activeSource + "/perform-again", performKey, validPerformBody);
        assertEquals(201, pathSourcePerform.status, pathSourcePerform.body);
        assertEquals(finishedSourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(activeSource)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and status='finished' and deleted_at is null", Integer.class, UUID.fromString(activeSource)));
        assertEquals("source note", jdbc.queryForObject("select notes from workout_exercises where id=?", String.class, UUID.fromString(activeEntry)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and repetitions=8 and weight_kg=42.5 and is_completed and rest_seconds=17", Integer.class, UUID.fromString(activeSet)));
        assertEquals(2, latestChanges(owner.id).size());
        assertEquals(routineCopy, latestChanges(owner.id).get(0).path("aggregate_id").asText());
        assertEquals("delete", latestChanges(owner.id).get(0).path("operation").asText());
        assertEquals(workoutCopy, latestChanges(owner.id).get(1).path("aggregate_id").asText());
        assertEquals("upsert", latestChanges(owner.id).get(1).path("operation").asText());
        assertEquals(workoutCopy, jdbc.queryForObject("select id from workouts where owner_id=? and status='active' and deleted_at is null", UUID.class, owner.id).toString());

        String routineFromWorkout = id(), savedEntry = id(), savedSet = id();
        String validSaveBody = "{\"id\":\"" + routineFromWorkout + "\",\"name\":\"Path source copy\",\"childIds\":[{\"sourceExerciseId\":\"" + activeEntry + "\",\"id\":\"" + savedEntry + "\",\"sets\":[{\"sourceSetId\":\"" + activeSet + "\",\"id\":\"" + savedSet + "\"}]}]}";
        UUID saveKey = UUID.randomUUID();
        Reply bodySourceSave = request(owner, "POST", "/v1/workouts/" + activeSource + "/save-as-routine", saveKey, validSaveBody.substring(0, validSaveBody.length() - 1) + ",\"sourceWorkoutId\":\"" + activeSource + "\"}");
        assertEquals(422, bodySourceSave.status, bodySourceSave.body);
        assertEquals("validation_error", bodySourceSave.node().path("errorCode").asText());
        String activeBeforeSaveAs = jdbc.queryForObject("select id from workouts where owner_id=? and status='active' and deleted_at is null", UUID.class, owner.id).toString();
        Reply pathSourceSave = request(owner, "POST", "/v1/workouts/" + activeSource + "/save-as-routine", saveKey, validSaveBody);
        assertEquals(201, pathSourceSave.status, pathSourceSave.body);
        assertEquals(activeBeforeSaveAs, jdbc.queryForObject("select id from workouts where owner_id=? and status='active' and deleted_at is null", UUID.class, owner.id).toString());
        assertEquals(finishedSourceRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(activeSource)));
        assertEquals("source note", jdbc.queryForObject("select notes from workout_exercises where id=?", String.class, UUID.fromString(activeEntry)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets where id=? and repetitions=8 and weight_kg=42.5 and is_completed and rest_seconds=17", Integer.class, UUID.fromString(activeSet)));
        assertEquals(1, latestChanges(owner.id).size());
        assertEquals("routine", latestChanges(owner.id).get(0).path("aggregate_type").asText());
        assertEquals(routineFromWorkout, latestChanges(owner.id).get(0).path("aggregate_id").asText());
    }

    @Test
    void resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection() {
        Account account = account();
        UUID key = UUID.randomUUID();
        String root = id();
        String raw = "{ \"id\" : \"" + root + "\" }";
        Reply first = request(account, "POST", "/v1/routines", key, raw);
        assertEquals(201, first.status, first.body);
        int batches = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply exactRetry = request(account, "POST", "/v1/routines", key, raw);
        assertEquals(201, exactRetry.status, exactRetry.body);
        assertArrayEquals(first.bytes, exactRetry.bytes);
        assertEquals(batches, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        String changedWhitespace = "{\"id\":\"" + root + "\" }";
        Reply changedBytes = request(account, "POST", "/v1/routines", key, changedWhitespace);
        assertEquals(409, changedBytes.status, changedBytes.body);
        assertEquals("idempotency_key_reused", changedBytes.node().path("errorCode").asText());
        Reply changedMethod = request(account, "PUT", "/v1/routines/" + root, key, routineInput(root, "draft", true, "[]"));
        assertEquals(409, changedMethod.status, changedMethod.body);
        Reply changedRoute = request(account, "POST", "/v1/routines/" + root + "/save", key, "{\"name\":\"now eligible?\"}");
        assertEquals(409, changedRoute.status, changedRoute.body);
        Reply laterEdit = send(account, "PUT", "/v1/routines/" + root, routineInput(root, "later canonical value", true, "[]"));
        assertEquals(200, laterEdit.status, laterEdit.body);
        int batchesAfterLaterEdit = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply replayAfterEdit = request(account, "POST", "/v1/routines", key, raw);
        assertEquals(201, replayAfterEdit.status, replayAfterEdit.body);
        assertArrayEquals(first.bytes, replayAfterEdit.bytes, "replay returns the original canonical resource, not a fresh projection");
        assertEquals("later canonical value", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(batchesAfterLaterEdit, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        String workout = id(), entry = id(), set = id();
        assertEquals(201, send(account, "POST", "/v1/workouts", "{\"id\":\"" + workout + "\",\"name\":\"numeric identity\",\"startedAt\":\"2025-02-01T00:00:00Z\"}").status);
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String integerWeight = "[" + workoutExercise(entry, exercise, 0, "", "30", "[" + workoutSet(set, 0, "null", "0", false, "null") + "]") + "]";
        String decimalWeight = integerWeight.replace("\"weightKg\":0,", "\"weightKg\":0.0,");
        UUID numberKey = UUID.randomUUID();
        String integralBody = workoutInput(workout, "numeric identity", "active", "2025-02-01T00:00:00Z", integerWeight);
        String decimalBody = workoutInput(workout, "numeric identity", "active", "2025-02-01T00:00:00Z", decimalWeight);
        assertEquals(200, request(account, "PUT", "/v1/workouts/" + workout, numberKey, integralBody).status);
        String reorderedNumberBody = "{\"name\":\"numeric identity\",\"id\":\"" + workout + "\",\"status\":\"active\",\"startedAt\":\"2025-02-01T00:00:00Z\",\"exercises\":" + integerWeight + "}";
        Reply keyOrderConflict = request(account, "PUT", "/v1/workouts/" + workout, numberKey, reorderedNumberBody);
        assertEquals(409, keyOrderConflict.status, keyOrderConflict.body);
        assertEquals("idempotency_key_reused", keyOrderConflict.node().path("errorCode").asText());
        Reply numberSpellingConflict = request(account, "PUT", "/v1/workouts/" + workout, numberKey, decimalBody);
        assertEquals(409, numberSpellingConflict.status, numberSpellingConflict.body);
        assertEquals("idempotency_key_reused", numberSpellingConflict.node().path("errorCode").asText());

        UUID rejectKey = UUID.randomUUID();
        String missing = id();
        int batchesBeforeReject = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply rejected = request(account, "DELETE", "/v1/routines/" + missing, rejectKey, null);
        assertEquals(404, rejected.status, rejected.body);
        assertEquals(batchesBeforeReject, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
        assertEquals("not_found", rejected.node().path("errorCode").asText());
        String importJson = "{\"id\":\"" + missing + "\"}";
        assertEquals(201, send(account, "POST", "/v1/routines", importJson).status);
        int batchesAfterStateChange = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply rejectionReplay = request(account, "DELETE", "/v1/routines/" + missing, rejectKey, null);
        assertEquals(404, rejectionReplay.status, rejectionReplay.body);
        assertEquals(rejected.body, rejectionReplay.body);
        assertEquals(batchesAfterStateChange, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
        assertFalse(rejectionReplay.body.contains(missing), "error response must not disclose root identity");

        String bodylessRoot = id();
        assertEquals(201, send(account, "POST", "/v1/routines", "{\"id\":\"" + bodylessRoot + "\"}").status);
        UUID deleteKey = UUID.randomUUID();
        Reply deleted = request(account, "DELETE", "/v1/routines/" + bodylessRoot, deleteKey, null);
        assertEquals(204, deleted.status, deleted.body);
        assertEquals(0, deleted.bytes.length);
        Reply deletedReplay = request(account, "DELETE", "/v1/routines/" + bodylessRoot, deleteKey, null);
        assertEquals(204, deletedReplay.status, deletedReplay.body);
        assertArrayEquals(deleted.bytes, deletedReplay.bytes);
        assertEquals(1, jdbc.queryForObject("select count(*) from resource_mutations where owner_id=? and idempotency_key=? and octet_length(request_body)=0 and response_status=204 and octet_length(response_body)=0", Integer.class, account.id, deleteKey));
        String emptyObjectRoot = id();
        assertEquals(201, send(account, "POST", "/v1/routines", "{\"id\":\"" + emptyObjectRoot + "\"}").status);
        UUID unsupportedBodyKey = UUID.randomUUID();
        Reply unsupportedBody = request(account, "DELETE", "/v1/routines/" + emptyObjectRoot, unsupportedBodyKey, "{}");
        assertEquals(422, unsupportedBody.status, unsupportedBody.body);
        assertEquals("validation_error", unsupportedBody.node().path("errorCode").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from resource_mutations where owner_id=? and idempotency_key=?", Integer.class, account.id, unsupportedBodyKey));
        Reply validAfterUnsupported = request(account, "DELETE", "/v1/routines/" + emptyObjectRoot, unsupportedBodyKey, null);
        assertEquals(204, validAfterUnsupported.status, validAfterUnsupported.body);
    }

    @Test
    void structuralAndHeaderFailuresAreNonDurableAndUseContractStatuses() {
        Account account = account();
        String root = id();
        UUID key = UUID.randomUUID();
        String valid = "{\"id\":\"" + root + "\"}";
        Reply missingKey = requestWithKey(account, "POST", "/v1/routines", null, valid);
        assertEquals(400, missingKey.status, missingKey.body);
        assertEquals("invalid_request", missingKey.node().path("errorCode").asText());
        Reply malformedKey = requestWithKey(account, "POST", "/v1/routines", "not-a-uuid", valid);
        assertEquals(422, malformedKey.status, malformedKey.body);
        assertEquals("validation_error", malformedKey.node().path("errorCode").asText());

        Reply malformedJson = request(account, "POST", "/v1/routines", key, "{broken");
        assertEquals(400, malformedJson.status, malformedJson.body);
        assertEquals("invalid_request", malformedJson.node().path("errorCode").asText());
        Reply duplicateKey = request(account, "POST", "/v1/routines", key, "{\"id\":\"" + root + "\",\"id\":\"" + id() + "\"}");
        assertEquals(400, duplicateKey.status, duplicateKey.body);
        assertEquals("invalid_request", duplicateKey.node().path("errorCode").asText());
        byte[] malformedUtf8 = new byte[]{'{', '\"', 'i', 'd', '\"', ':', '\"', (byte) 0xc3, 0x28, '\"', '}'};
        Reply utf8Rejected = requestBytes(account, "POST", "/v1/routines", key, malformedUtf8);
        assertEquals(400, utf8Rejected.status, utf8Rejected.body);
        assertEquals("invalid_request", utf8Rejected.node().path("errorCode").asText());
        Reply unknownField = request(account, "POST", "/v1/routines", key, "{\"id\":\"" + root + "\",\"ownerId\":\"" + id() + "\"}");
        assertEquals(422, unknownField.status, unknownField.body);
        assertEquals("validation_error", unknownField.node().path("errorCode").asText());
        Reply validRetry = request(account, "POST", "/v1/routines", key, valid);
        assertEquals(201, validRetry.status, validRetry.body);
        assertEquals(root, validRetry.node().path("id").asText());

        Reply uppercasePath = request(account, "DELETE", "/v1/routines/" + root.toUpperCase(Locale.ROOT), UUID.randomUUID(), null);
        assertEquals(422, uppercasePath.status, uppercasePath.body);
        assertEquals("validation_error", uppercasePath.node().path("errorCode").asText());
        Reply unsupportedQuery = request(account, "POST", "/v1/routines?unexpected=1", UUID.randomUUID(), "{\"id\":\"" + id() + "\"}");
        assertEquals(400, unsupportedQuery.status, unsupportedQuery.body);
        assertEquals("invalid_request", unsupportedQuery.node().path("errorCode").asText());
    }

    @Test
    void sameResourceKeyIsAccountScopedAndIndependentFromSyncNamespace() {
        Account first = account(), second = account();
        UUID sharedKey = UUID.randomUUID();
        String rootOne = id(), rootTwo = id();
        Reply one = request(first, "POST", "/v1/routines", sharedKey, "{\"id\":\"" + rootOne + "\"}");
        Reply two = request(second, "POST", "/v1/routines", sharedKey, "{\"id\":\"" + rootTwo + "\"}");
        assertEquals(201, one.status, one.body);
        assertEquals(201, two.status, two.body);
        assertEquals(rootOne, one.node().path("id").asText());
        assertEquals(rootTwo, two.node().path("id").asText());

        // Keep the sync target and payload target identical, as required by the workout.start contract.
        String syncRoot = id();
        String syncMutation = "{\"mutationId\":\"" + sharedKey + "\",\"type\":\"workout.start\",\"aggregateType\":\"workout\",\"aggregateId\":\"" + syncRoot + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + syncRoot + "\",\"name\":\"separate namespace\",\"startedAt\":\"2025-04-01T00:00:00Z\"}}";
        Reply sync = send(first, "POST", "/v1/sync/push", jsonEnvelope(syncMutation));
        assertEquals(200, sync.status, sync.body);
        assertEquals("accepted", sync.node().path("results").get(0).path("status").asText());
        Reply resourceRetry = request(first, "POST", "/v1/routines", sharedKey, "{\"id\":\"" + rootOne + "\"}");
        assertEquals(201, resourceRetry.status, resourceRetry.body);
        assertEquals(one.body, resourceRetry.body);
    }

    private String jsonEnvelope(String mutation) {
        return "{\"mutations\":[" + mutation + "]}";
    }

    @Test
    void resourceAndAuthenticationIdempotencyNamespacesAreIndependent() throws Exception {
        UUID sharedKey = UUID.randomUUID();
        HttpRequest challengeRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/google/challenge"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> challengeResponse = HttpClient.newHttpClient().send(challengeRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, challengeResponse.statusCode(), challengeResponse.body());
        JsonNode challenge = json.readTree(challengeResponse.body());
        String token = "namespace-google-token";
        when(verifier.verify(token, challenge.path("nonce").asText())).thenReturn(new Verified("namespace-google-subject", null));
        String exchangeBody = "{\"challengeId\":\"" + challenge.path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}";
        HttpRequest exchange = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/google"))
                .header("Content-Type", "application/json").header("Idempotency-Key", sharedKey.toString())
                .POST(HttpRequest.BodyPublishers.ofString(exchangeBody)).build();
        HttpResponse<byte[]> grant = HttpClient.newHttpClient().send(exchange, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, grant.statusCode(), new String(grant.body(), StandardCharsets.UTF_8));
        JsonNode grantBody = json.readTree(grant.body());
        Account account = new Account(UUID.fromString(grantBody.path("account").path("id").asText()), grantBody.path("accessToken").asText());

        String root = id();
        Reply resource = request(account, "POST", "/v1/routines", sharedKey, "{\"id\":\"" + root + "\"}");
        assertEquals(201, resource.status, resource.body);
        HttpResponse<byte[]> authReplay = HttpClient.newHttpClient().send(exchange, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, authReplay.statusCode());
        assertArrayEquals(grant.body(), authReplay.body());
        assertEquals(1, jdbc.queryForObject("select count(*) from routines where id=? and owner_id=?", Integer.class, UUID.fromString(root), account.id));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, account.id),
                "direct resource execution must not synthesize a sync mutation");
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_idempotency where request_key=?", Integer.class, sharedKey));
    }

    @Test
    void concurrentIdenticalDirectRequestsExecuteOnceAndReplayTheOriginalResource() throws Exception {
        Account account = account();
        UUID key = UUID.randomUUID();
        String root = id();
        String body = "{ \"id\" : \"" + root + "\" }";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Reply> left = pool.submit(() -> {
                gate.await();
                return request(account, "POST", "/v1/routines", key, body);
            });
            Future<Reply> right = pool.submit(() -> {
                gate.await();
                return request(account, "POST", "/v1/routines", key, body);
            });
            gate.countDown();
            Reply a = left.get(30, TimeUnit.SECONDS), b = right.get(30, TimeUnit.SECONDS);
            assertEquals(201, a.status, a.body);
            assertEquals(201, b.status, b.body);
            assertArrayEquals(a.bytes, b.bytes);
            assertEquals(1, jdbc.queryForObject("select count(*) from routines where owner_id=? and id=? and deleted_at is null", Integer.class, account.id, UUID.fromString(root)));
            assertEquals(1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDirectAndSyncReplacementsSerializeWholeSameAccountAggregates() throws Exception {
        Account account = account();
        String root = id();
        assertEquals(201, send(account, "POST", "/v1/routines", "{\"id\":\"" + root + "\"}").status);
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String entryA = id(), entryB = id(), entryC = id(), entryD = id();
        String a = routineInput(root, "direct A", true, "[" + routineExercise(entryA, exercise, 0, "[]") + "]");
        String b = routineInput(root, "direct B", true, "[" + routineExercise(entryB, exercise, 0, "[]") + "]");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Reply> left = pool.submit(() -> {
                gate.await();
                return send(account, "PUT", "/v1/routines/" + root, a);
            });
            Future<Reply> right = pool.submit(() -> {
                gate.await();
                return send(account, "PUT", "/v1/routines/" + root, b);
            });
            gate.countDown();
            assertEquals(200, left.get(30, TimeUnit.SECONDS).status);
            assertEquals(200, right.get(30, TimeUnit.SECONDS).status);
        } finally {
            pool.shutdownNow();
        }
        assertWholeRoutineIsOneOf(account.id, root, Map.of("direct A", entryA, "direct B", entryB));
        assertEquals(3, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));

        String c = routineInput(root, "direct before sync", true, "[" + routineExercise(entryC, exercise, 0, "[]") + "]");
        String d = routineInput(root, "sync winner candidate", true, "[" + routineExercise(entryD, exercise, 0, "[]") + "]");
        String syncMutation = "{\"mutationId\":\"" + id() + "\",\"type\":\"routine.upsert\",\"aggregateType\":\"routine\",\"aggregateId\":\"" + root + "\",\"localGeneration\":4,\"payload\":" + d + "}";
        CountDownLatch syncGate = new CountDownLatch(1);
        ExecutorService mixed = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> direct = mixed.submit(() -> {
                syncGate.await();
                return send(account, "PUT", "/v1/routines/" + root, c);
            });
            Future<Reply> sync = mixed.submit(() -> {
                syncGate.await();
                return send(account, "POST", "/v1/sync/push", jsonEnvelope(syncMutation));
            });
            syncGate.countDown();
            assertEquals(200, direct.get(30, TimeUnit.SECONDS).status);
            Reply syncResponse = sync.get(30, TimeUnit.SECONDS);
            assertEquals(200, syncResponse.status, syncResponse.body);
            assertEquals("accepted", syncResponse.node().path("results").get(0).path("status").asText());
        } finally {
            mixed.shutdownNow();
        }
        assertWholeRoutineIsOneOf(account.id, root, Map.of("direct before sync", entryC, "sync winner candidate", entryD));
        assertEquals(5, jdbc.queryForObject("select revision from routines where id=?", Integer.class, UUID.fromString(root)));
        assertEquals(5, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
    }

    private void assertWholeRoutineIsOneOf(UUID owner, String root, Map<String, String> allowed) {
        String name = jdbc.queryForObject("select name from routines where owner_id=? and id=? and deleted_at is null", String.class, owner, UUID.fromString(root));
        String expectedEntry = allowed.get(name);
        assertNotNull(expectedEntry, "unexpected winning complete root name: " + name);
        List<UUID> entries = jdbc.query("select id from routine_exercises where routine_id=? order by position", (rs, row) -> rs.getObject(1, UUID.class), UUID.fromString(root));
        assertEquals(List.of(UUID.fromString(expectedEntry)), entries, "root child state must match the same complete replacement, never a field merge");
    }

    @Test
    void directAcceptedAndRejectedOutcomesReplayAcrossRealJvmRestart() throws Exception {
        Account account = account();
        int childPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            childPort = socket.getLocalPort();
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process first = startProcess(javaExecutable, classpath, childPort);
        String createdId = id(), updatedId = id(), deletedId = id(), missingId = id();
        UUID createKey = UUID.randomUUID(), updateKey = UUID.randomUUID(), deleteKey = UUID.randomUUID(), rejectKey = UUID.randomUUID();
        String createBody = "{\"id\":\"" + createdId + "\"}";
        String updateBody = routineInput(updatedId, "before restart", true, "[]");
        String deleteBody = "{\"id\":\"" + deletedId + "\"}";
        Reply created, updated, deleted, rejected;
        try {
            awaitProcess(childPort);
            created = requestAt(childPort, account, "POST", "/v1/routines", createKey, createBody);
            assertEquals(201, created.status, created.body);
            assertEquals(201, requestAt(childPort, account, "POST", "/v1/routines", UUID.randomUUID(), deleteBody).status);
            // Create then update an existing routine for the 200 replay category.
            Reply updateRoot = requestAt(childPort, account, "POST", "/v1/routines", UUID.randomUUID(), "{\"id\":\"" + updatedId + "\"}");
            assertEquals(201, updateRoot.status, updateRoot.body);
            updated = requestAt(childPort, account, "PUT", "/v1/routines/" + updatedId, updateKey, updateBody);
            assertEquals(200, updated.status, updated.body);
            deleted = requestAt(childPort, account, "DELETE", "/v1/routines/" + deletedId, deleteKey, null);
            assertEquals(204, deleted.status, deleted.body);
            rejected = requestAt(childPort, account, "DELETE", "/v1/routines/" + missingId, rejectKey, null);
            assertEquals(404, rejected.status, rejected.body);
            assertEquals(201, requestAt(childPort, account, "POST", "/v1/routines", UUID.randomUUID(), "{\"id\":\"" + missingId + "\"}").status);
        } finally {
            stopProcess(first);
        }

        int batchesBeforeReplay = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Process second = startProcess(javaExecutable, classpath, childPort);
        try {
            awaitProcess(childPort);
            assertReplay(created, requestAt(childPort, account, "POST", "/v1/routines", createKey, createBody), 201);
            assertReplay(updated, requestAt(childPort, account, "PUT", "/v1/routines/" + updatedId, updateKey, updateBody), 200);
            assertReplay(deleted, requestAt(childPort, account, "DELETE", "/v1/routines/" + deletedId, deleteKey, null), 204);
            assertReplay(rejected, requestAt(childPort, account, "DELETE", "/v1/routines/" + missingId, rejectKey, null), 404);
            assertEquals(batchesBeforeReplay, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
            assertEquals("before restart", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(updatedId)));
        } finally {
            stopProcess(second);
        }
    }

    private void assertReplay(Reply original, Reply replay, int status) {
        assertEquals(status, original.status, original.body);
        assertEquals(status, replay.status, replay.body);
        assertArrayEquals(original.bytes, replay.bytes);
    }

    private Process startProcess(String java, String classpath, int port) {
        try {
            ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, "com.dragosfotache.gtbackend.GtBackendApplication", "--server.port=" + port);
            Map<String, String> env = builder.environment();
            env.put("DB_URL", pg.getJdbcUrl());
            env.put("DB_USER", pg.getUsername());
            env.put("DB_PASSWORD", pg.getPassword());
            env.put("AUTH_ENCRYPTION_KEY", encryptionKey);
            env.put("GOOGLE_OAUTH_AUDIENCE", "integration-audience");
            env.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            env.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        } catch (Exception e) {
            throw new AssertionError("Unable to start isolated direct-resource JVM", e);
        }
    }

    private void awaitProcess(int port) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < until) {
            try {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sync/push"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                if (HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 401)
                    return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("fresh direct-resource HTTP process did not become ready");
    }

    private void stopProcess(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            }
            assertTrue(process.exitValue() == 0 || process.exitValue() == 143, "test-created HTTP process did not stop cleanly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void postWritePublicationFaultRollsBackDirectActiveRootReplacementAndUnchangedRetrySucceeds() {
        Account account = account();
        String oldActive = id(), target = id();
        Reply old = send(account, "POST", "/v1/workouts", "{\"id\":\"" + oldActive + "\",\"name\":\"prior active\",\"startedAt\":\"2025-05-01T00:00:00Z\"}");
        assertEquals(201, old.status, old.body);
        long cursorBefore = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id);
        int batchesBefore = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        UUID key = UUID.randomUUID();
        String body = "{\"id\":\"" + target + "\",\"name\":\"retry me\",\"startedAt\":\"2025-05-02T00:00:00Z\"}";
        String marker = "qa_direct_publication_fault";
        jdbc.execute("create function qa_direct_publication_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger qa_direct_publication_fault before insert on sync_change_batches for each row execute function qa_direct_publication_fault()");
        Reply failed;
        try {
            failed = request(account, "POST", "/v1/workouts", key, body);
        } finally {
            jdbc.execute("drop trigger qa_direct_publication_fault on sync_change_batches");
            jdbc.execute("drop function qa_direct_publication_fault()");
        }
        assertEquals(500, failed.status, failed.body);
        assertEquals("internal_error", failed.node().path("errorCode").asText());
        assertFalse(failed.body.contains(marker));
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is null and status='active'", Integer.class, UUID.fromString(oldActive)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(target)));
        assertEquals(0, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' and aggregate_id=?", Integer.class, UUID.fromString(target)));
        assertEquals(cursorBefore, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id));
        assertEquals(batchesBefore, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        Reply retried = request(account, "POST", "/v1/workouts", key, body);
        assertEquals(201, retried.status, retried.body);
        assertEquals(target, retried.node().path("id").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and deleted_at is not null", Integer.class, UUID.fromString(oldActive)));
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_changes where account_id=? and cursor=(select committed_cursor from account_sync_state where account_id=?)", Integer.class, account.id, account.id));

        String sourceRoutine = id(), sourceEntry = id(), sourceSet = id();
        assertEquals(201, send(account, "POST", "/v1/routines", "{\"id\":\"" + sourceRoutine + "\"}").status);
        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String template = "[" + routineExercise(sourceEntry, exercise, 0, "[" + routineSet(sourceSet, 0, "null", "0") + "]") + "]";
        assertEquals(200, send(account, "PUT", "/v1/routines/" + sourceRoutine, routineInput(sourceRoutine, "fault source", true, template)).status);
        assertEquals(200, send(account, "POST", "/v1/routines/" + sourceRoutine + "/save", "{\"name\":\"fault source\"}").status);
        long sourceRevision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(sourceRoutine));
        long copyCursor = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id);
        int copyBatches = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        String copyRoot = id(), copyEntry = id(), copySet = id();
        UUID copyKey = UUID.randomUUID();
        String copyBody = "{\"id\":\"" + copyRoot + "\",\"startedAt\":\"2025-05-03T00:00:00Z\",\"childIds\":[{\"sourceExerciseId\":\"" + sourceEntry + "\",\"id\":\"" + copyEntry + "\",\"sets\":[{\"sourceSetId\":\"" + sourceSet + "\",\"id\":\"" + copySet + "\"}]}]}";
        jdbc.execute("create function qa_direct_publication_fault() returns trigger language plpgsql as $$ begin raise exception '" + marker + "' using errcode='XX000'; end $$");
        jdbc.execute("create trigger qa_direct_publication_fault before insert on sync_change_batches for each row execute function qa_direct_publication_fault()");
        Reply copyFailed;
        try {
            copyFailed = request(account, "POST", "/v1/routines/" + sourceRoutine + "/start-workout", copyKey, copyBody);
        } finally {
            jdbc.execute("drop trigger qa_direct_publication_fault on sync_change_batches");
            jdbc.execute("drop function qa_direct_publication_fault()");
        }
        assertEquals(500, copyFailed.status, copyFailed.body);
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where id=?", Integer.class, UUID.fromString(copyRoot)));
        assertEquals(0, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' and aggregate_id=?", Integer.class, UUID.fromString(copyRoot)));
        assertEquals(0, jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' and child_id in (?,?,?)", Integer.class, UUID.fromString(copyRoot), UUID.fromString(copyEntry), UUID.fromString(copySet)));
        assertEquals(target, jdbc.queryForObject("select id from workouts where owner_id=? and status='active' and deleted_at is null", UUID.class, account.id).toString());
        assertEquals(sourceRevision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(sourceRoutine)));
        assertEquals(copyCursor, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id));
        assertEquals(copyBatches, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
        Reply copyRetry = request(account, "POST", "/v1/routines/" + sourceRoutine + "/start-workout", copyKey, copyBody);
        assertEquals(201, copyRetry.status, copyRetry.body);
        assertEquals(2, latestChanges(account.id).size());
        assertEquals(target, latestChanges(account.id).get(0).path("aggregate_id").asText());
        assertEquals("delete", latestChanges(account.id).get(0).path("operation").asText());
        assertEquals(copyRoot, latestChanges(account.id).get(1).path("aggregate_id").asText());
        assertEquals("upsert", latestChanges(account.id).get(1).path("operation").asText());
    }

    @Test
    void directFinishWithoutCompletedSetRejectsWithoutEffectsAndReplaysAfterEligibilityChanges() {
        Account account = account();
        String root = id();
        Reply started = send(account, "POST", "/v1/workouts", "{\"id\":\"" + root + "\",\"name\":\"empty finish\",\"startedAt\":\"2025-07-01T00:00:00Z\"}");
        assertEquals(201, started.status, started.body);
        UUID key = UUID.randomUUID();
        String finishBody = "{\"finishedAt\":\"2025-07-01T01:00:00Z\"}";
        long initialRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(root));
        long initialCursor = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id);
        int initialBatches = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply rejected = request(account, "POST", "/v1/workouts/" + root + "/finish", key, finishBody);
        assertEquals(422, rejected.status, rejected.body);
        assertEquals("validation_error", rejected.node().path("errorCode").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from workouts where id=? and status='active' and finished_at is null and deleted_at is null", Integer.class, UUID.fromString(root)));
        assertEquals(0, jdbc.queryForObject("select count(*) from workout_exercises where workout_id=?", Integer.class, UUID.fromString(root)));
        assertEquals(initialRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(root)));
        assertEquals(initialCursor, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id));
        assertEquals(initialBatches, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        String exercise = jdbc.queryForObject("select id from exercises where owner_id is null order by id limit 1", UUID.class).toString();
        String entry = id(), set = id();
        String children = "[" + workoutExercise(entry, exercise, 0, "", "30", "[" + workoutSet(set, 0, "1", "1", true, "null") + "]") + "]";
        Reply completed = send(account, "PUT", "/v1/workouts/" + root, workoutInput(root, "complete now", "active", "2025-07-01T00:00:00Z", children));
        assertEquals(200, completed.status, completed.body);
        int batchesAfterEdit = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        Reply rejectionReplay = request(account, "POST", "/v1/workouts/" + root + "/finish", key, finishBody);
        assertEquals(422, rejectionReplay.status, rejectionReplay.body);
        assertEquals(rejected.body, rejectionReplay.body);
        assertEquals("active", jdbc.queryForObject("select status from workouts where id=?", String.class, UUID.fromString(root)));
        assertEquals(batchesAfterEdit, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        Reply finishEligible = send(account, "POST", "/v1/workouts/" + root + "/finish", "{\"finishedAt\":\"2025-07-01T01:00:00Z\"}");
        assertEquals(200, finishEligible.status, finishEligible.body);
        assertEquals("finished", finishEligible.node().path("status").asText());
    }

    @Test
    void transientSerializationFailureIs503NondurableAndUnchangedDirectRetrySucceeds() {
        Account account = account();
        String root = id();
        Reply created = send(account, "POST", "/v1/routines", "{\"id\":\"" + root + "\"}");
        assertEquals(201, created.status, created.body);
        int beforeBatches = jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id);
        long beforeCursor = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id);
        long beforeRevision = jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root));
        String body = routineInput(root, "serialization retry", true, "[]");
        UUID key = UUID.randomUUID();
        jdbc.execute("create function qa_direct_serialization_fault() returns trigger language plpgsql as $$ begin raise exception 'qa injected transient serialization fault' using errcode='40001'; end $$");
        jdbc.execute("create trigger qa_direct_serialization_fault before insert on sync_change_batches for each row execute function qa_direct_serialization_fault()");
        Reply failed;
        try {
            failed = request(account, "PUT", "/v1/routines/" + root, key, body);
        } finally {
            jdbc.execute("drop trigger qa_direct_serialization_fault on sync_change_batches");
            jdbc.execute("drop function qa_direct_serialization_fault()");
        }
        assertEquals(503, failed.status, failed.body);
        assertEquals("temporarily_unavailable", failed.node().path("errorCode").asText());
        assertEquals("", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(beforeRevision, jdbc.queryForObject("select revision from routines where id=?", Long.class, UUID.fromString(root)));
        assertEquals(beforeCursor, jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id));
        assertEquals(beforeBatches, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));

        Reply retry = request(account, "PUT", "/v1/routines/" + root, key, body);
        assertEquals(200, retry.status, retry.body);
        assertEquals("serialization retry", retry.node().path("name").asText());
        assertEquals("serialization retry", jdbc.queryForObject("select name from routines where id=?", String.class, UUID.fromString(root)));
        assertEquals(beforeBatches + 1, jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id));
    }

    @Test
    void directProtectedWriteIsFencedImmediatelyByAcceptedSessionLogout() throws Exception {
        Account account = account();
        String root = id();
        UUID key = UUID.randomUUID();
        // Logout needs this synthetic session's refresh credential; create a sibling valid refresh credential.
        UUID session = jdbc.queryForObject("select session_id from auth_credentials where fingerprint=?", UUID.class, crypto.digest(account.token));
        String refresh = UUID.randomUUID().toString();
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'refresh',clock_timestamp(),clock_timestamp()+interval '1 day')", crypto.digest(refresh), session);
        try {
            var logout = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/logout"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refresh + "\"}"))
                    .build();
            int status = HttpClient.newHttpClient().send(logout, HttpResponse.BodyHandlers.discarding()).statusCode();
            assertEquals(204, status);
            Reply protectedWrite = request(account, "POST", "/v1/routines", key, "{\"id\":\"" + root + "\"}");
            assertEquals(401, protectedWrite.status, protectedWrite.body);
            assertEquals("authentication_required", protectedWrite.node().path("errorCode").asText());
            assertEquals(0, jdbc.queryForObject("select count(*) from routines where id=?", Integer.class, UUID.fromString(root)));
            assertEquals(0, jdbc.queryForObject("select count(*) from sync_change_batches b join accounts a on a.id=b.account_id where a.id=?", Integer.class, account.id));
        } finally {
            // No test-created child process; logout itself is the only state transition.
        }
    }
}
