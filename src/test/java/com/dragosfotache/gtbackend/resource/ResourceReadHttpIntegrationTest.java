package com.dragosfotache.gtbackend.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseEquipment;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseMuscle;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceReadHttpIntegrationTest {
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUNDLED_REVERSE_FLY = "960b59d9-e989-4bf1-ad45-37f1e0868de2";
    private static final AtomicReference<ReadGate> ACTIVE_READ_GATE = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "read-integration-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;

    @MockitoSpyBean
    JdbcTemplate jdbc;

    @Autowired
    AuthCrypto crypto;

    @org.junit.jupiter.api.BeforeEach
    void installTargetedReadGate() {
        ACTIVE_READ_GATE.set(null);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            ReadGate gate = ACTIVE_READ_GATE.get();
            if (gate != null) {
                String sql = invocation.getArgument(0);
                if (gate.matches(sql, invocation.getArguments())) gate.afterRootQuery();
            }
            return result;
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    private record Account(UUID id, String accessToken, String refreshToken) {}
    private static final class ReadGate {
        final UUID rootId;
        final String sqlFragment;
        final RuntimeException failure;
        final CountDownLatch rootQueryReturned = new CountDownLatch(1);
        final CountDownLatch continueRead = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();

        ReadGate(UUID rootId, String sqlFragment, RuntimeException failure) {
            this.rootId = rootId;
            this.sqlFragment = sqlFragment;
            this.failure = failure;
        }

        boolean matches(String sql, Object[] invocationArguments) {
            if (!sql.toLowerCase(java.util.Locale.ROOT).contains(sqlFragment)) return false;
            boolean targetFound = false;
            for (int index = 2; index < invocationArguments.length; index++) {
                Object argument = invocationArguments[index];
                if (rootId.equals(argument)) targetFound = true;
                if (argument instanceof Object[] expandedArguments && java.util.Arrays.asList(expandedArguments).contains(rootId))
                    targetFound = true;
            }
            return targetFound && used.compareAndSet(false, true);
        }

        void afterRootQuery() {
            if (failure != null) throw failure;
            rootQueryReturned.countDown();
            try {
                if (!continueRead.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("Read gate was not released in time");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Read gate interrupted", e);
            }
        }
    }

    private record Reply(int status, byte[] bytes) {
        JsonNode json() {
            try {
                return JSON.readTree(bytes);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON", e);
            }
        }
    }
    private record CustomExercise(UUID id, List<UUID> muscles, UUID equipment) {}
    private record ReadRoots(UUID exercise, UUID activeWorkout, UUID finishedWorkout, UUID draft, UUID saved, UUID blankSaved) {}

    private Account account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into accounts(id) values(?)", id);
        return session(id, "1 hour", false);
    }

    private Account session(UUID accountId, String lifetime, boolean revoked) {
        UUID sessionId = UUID.randomUUID();
        String accessToken = UUID.randomUUID().toString();
        String refreshToken = UUID.randomUUID().toString();
        jdbc.update("insert into auth_sessions(id,account_id,created_at,revoked_at) values(?,?,clock_timestamp(),?)",
                sessionId, accountId, revoked ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+(?::interval)),(?,?, 'refresh',clock_timestamp(),clock_timestamp()+interval '30 days')",
                crypto.digest(accessToken), sessionId, lifetime, crypto.digest(refreshToken), sessionId);
        return new Account(accountId, accessToken, refreshToken);
    }

    private Reply get(Account account, String path) {
        return request(account, "GET", path, null, null, null);
    }

    private Reply request(Account account, String method, String path, String body, String idempotencyKey, String bearerOverride) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json");
            String token = bearerOverride != null ? bearerOverride : account == null ? null : account.accessToken();
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8));
            builder.method(method, publisher);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP request failed: " + method + " " + path, e);
        }
    }

    private CustomExercise createCustomExercise(UUID owner, String name, String instructions) {
        UUID id = UUID.randomUUID();
        List<UUID> muscles = jdbc.query("select id from muscle_groups order by id limit 3", (rs, row) -> rs.getObject(1, UUID.class));
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        jdbc.update("insert into exercises(id,owner_id,is_user_created,name,type,category,difficulty_level,force_type,mechanics,instructions,created_at,updated_at,revision) " +
                        "values(?,?,true,?,'reps','conditioning','advanced','pull','compound',?::jsonb,clock_timestamp(),clock_timestamp(),3)",
                id, owner, name, instructions);
        jdbc.update("insert into exercise_muscle_groups(exercise_id,muscle_group_id,type) values(?,?,'secondary'),(?,?,'primary'),(?,?,'secondary')",
                id, muscles.get(0), id, muscles.get(2), id, muscles.get(1));
        jdbc.update("insert into exercise_equipment(exercise_id,equipment_id,num_items) values(?,?,null)", id, equipment);
        return new CustomExercise(id, muscles, equipment);
    }

    private ReadRoots createReadRoots(Account account, UUID customExercise) {
        UUID active = UUID.randomUUID(), finished = UUID.randomUUID(), draft = UUID.randomUUID(), saved = UUID.randomUUID(), blankSaved = UUID.randomUUID();
        jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,?,'active',5)",
                active, account.id(), "Active workout", java.sql.Timestamp.from(java.time.Instant.parse("2025-04-05T06:07:08.123Z")));
        jdbc.update("insert into workouts(id,owner_id,name,started_at,finished_at,status,revision) values(?,?,?, ?,?,'finished',9)",
                finished, account.id(), "Finished history", java.sql.Timestamp.from(java.time.Instant.parse("2025-04-06T06:07:08.456Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2025-04-06T07:08:09.789Z")));
        UUID activeEntry = UUID.randomUUID(), firstEntry = UUID.randomUUID(), secondEntry = UUID.randomUUID();
        jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,notes,rest_duration_seconds) values(?,?,?,0,?,45),(?,?,?,0,?,0),(?,?,?,1,?,90)",
                activeEntry, active, customExercise, "active values", firstEntry, finished, customExercise, "first occurrence", secondEntry, finished, customExercise, "second occurrence");
        UUID activeNullSet = UUID.randomUUID(), activeZeroSet = UUID.randomUUID();
        UUID firstSet = UUID.randomUUID(), secondSet = UUID.randomUUID(), thirdSet = UUID.randomUUID();
        jdbc.update("insert into workout_sets(id,workout_exercise_id,position,repetitions,weight_kg,rest_seconds,is_completed) values(?,?,0,null,null,null,false),(?,?,1,0,0,0,false),(?,?,0,0,0,null,true),(?,?,0,5,0,0,true),(?,?,1,8,42.5,17,true)",
                activeNullSet, activeEntry, activeZeroSet, activeEntry, firstSet, firstEntry, secondSet, secondEntry, thirdSet, secondEntry);
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,true,2),(?,?,?,false,4),(?,?,?,false,1)",
                draft, account.id(), "Draft", saved, account.id(), "Saved routine", blankSaved, account.id(), "");
        UUID draftEntry = UUID.randomUUID(), savedEntry = UUID.randomUUID(), routineSet = UUID.randomUUID();
        jdbc.update("insert into routine_exercises(id,routine_id,exercise_id,position,rest_duration_seconds) values(?,?,?,0,30),(?,?,?,0,75)",
                draftEntry, draft, customExercise, savedEntry, saved, customExercise);
        jdbc.update("insert into routine_sets(id,routine_exercise_id,position,repetitions,weight_kg) values(?,?,0,null,0),(?,?,0,0,null)",
                routineSet, draftEntry, UUID.randomUUID(), savedEntry);
        return new ReadRoots(customExercise, active, finished, draft, saved, blankSaved);
    }

    @Test
    void visibleExerciseReadsReturnPinnedBundleAndCompleteCustomCanonicalFields() {
        Account owner = account();
        Account other = account();
        CustomExercise custom = createCustomExercise(owner.id(), "QA custom exercise", "[\"first instruction\",\"second instruction\"]");

        Reply bundledReply = get(owner, "/v1/exercises/" + BUNDLED_REVERSE_FLY);
        assertEquals(200, bundledReply.status(), new String(bundledReply.bytes(), StandardCharsets.UTF_8));
        JsonNode bundled = bundledReply.json();
        assertEquals(BUNDLED_REVERSE_FLY, bundled.path("id").asText());
        assertEquals("Dumbbell Reverse Fly", bundled.path("name").asText());
        assertEquals("reps", bundled.path("type").asText());
        assertFalse(bundled.path("isUserCreated").asBoolean());
        assertFalse(bundled.has("revision"), "bundled catalogue values have no user-root revision");
        assertEquals("intermediate", bundled.path("difficultyLevel").asText());
        assertEquals("pull", bundled.path("forceType").asText());
        assertEquals("isolation", bundled.path("mechanics").asText());
        assertEquals("strength", bundled.path("category").asText());
        assertEquals("Shoulders", bundled.path("muscleGroups").get(0).path("name").asText());
        assertEquals("5181c3d4-3cfd-4e9e-9ae3-3de472e084bc", bundled.path("muscleGroups").get(0).path("id").asText());
        assertEquals("primary", bundled.path("muscleGroups").get(0).path("type").asText());
        assertEquals("Middle Back", bundled.path("muscleGroups").get(1).path("name").asText());
        assertEquals("cf81b51f-97d4-4cb2-ab3c-1d59472481ed", bundled.path("muscleGroups").get(1).path("id").asText());
        assertEquals("primary", bundled.path("muscleGroups").get(1).path("type").asText());
        assertEquals("Dumbbell", bundled.path("equipment").get(0).path("name").asText());
        assertEquals("22ad09f6-c689-4096-ad05-971ca8a6f0aa", bundled.path("equipment").get(0).path("id").asText());
        assertTrue(bundled.path("equipment").get(0).path("numItems").isNull());
        assertEquals(List.of(
                        "Stand with your feet shoulder width apart, holding a dumbbell in each hand. Bend at the waist until your torso is nearly parallel to the floor, keeping a slight bend in the knees. Let the dumbbells hang directly below you with your palms facing each other. This will be your starting position.",
                        "Keeping a slight bend in your elbows, raise the dumbbells out to your sides until your arms are parallel to the floor. Exhale as you perform this movement. Tip: Focus on squeezing your shoulder blades together at the top of the movement.",
                        "Hold the contracted position for a second at the top.",
                        "Slowly lower the dumbbells back to the starting position as you breathe in.",
                        "Repeat for the recommended amount of repetitions."),
                JSON.convertValue(bundled.path("instructions"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(200, get(other, "/v1/exercises/" + BUNDLED_REVERSE_FLY).status(), "bundled exercise is visible to every authenticated account");

        Reply customReply = get(owner, "/v1/exercises/" + custom.id());
        assertEquals(200, customReply.status(), new String(customReply.bytes(), StandardCharsets.UTF_8));
        JsonNode exercise = customReply.json();
        assertEquals(custom.id().toString(), exercise.path("id").asText());
        assertEquals("QA custom exercise", exercise.path("name").asText());
        assertEquals("reps", exercise.path("type").asText());
        assertTrue(exercise.path("isUserCreated").asBoolean());
        assertEquals(3, exercise.path("revision").asLong());
        assertEquals("conditioning", exercise.path("category").asText());
        assertEquals("advanced", exercise.path("difficultyLevel").asText());
        assertEquals("pull", exercise.path("forceType").asText());
        assertEquals("compound", exercise.path("mechanics").asText());
        assertEquals(List.of("first instruction", "second instruction"), JSON.convertValue(exercise.path("instructions"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(3, exercise.path("muscleGroups").size());
        List<String> roles = new ArrayList<>();
        List<String> actualMuscleIds = new ArrayList<>();
        for (JsonNode muscle : exercise.path("muscleGroups")) {
            roles.add(muscle.path("type").asText());
            actualMuscleIds.add(muscle.path("id").asText());
            assertFalse(muscle.path("name").asText().isBlank());
        }
        assertEquals(List.of("primary", "secondary", "secondary"), roles);
        assertEquals(List.of("Neck", "Lats", "Hamstrings"), exercise.path("muscleGroups").findValuesAsText("name"));
        assertEquals(List.of("2eed00c9-b475-4f28-b1c7-4b71812f841f", "1974ac3e-bdd2-4793-9364-68aa131fafaa", "1c2a5282-e7dd-4bcb-9cf9-249ef441878d"),
                actualMuscleIds, "primary role precedes secondary roles even when its UUID sorts after them; secondaries sort by UUID");
        assertEquals(1, exercise.path("equipment").size());
        assertEquals(custom.equipment().toString(), exercise.path("equipment").get(0).path("id").asText());
        assertEquals("Sandbag", exercise.path("equipment").get(0).path("name").asText());
        assertTrue(exercise.path("equipment").get(0).path("numItems").isNull());
        assertFalse(new String(customReply.bytes(), StandardCharsets.UTF_8).contains("owner_id"));
        assertFalse(new String(customReply.bytes(), StandardCharsets.UTF_8).contains("accountId"));

        CustomExercise nullInstructions = createCustomExercise(owner.id(), "Null instructions", null);
        CustomExercise emptyInstructions = createCustomExercise(owner.id(), "Empty instructions", "[]");
        assertTrue(get(owner, "/v1/exercises/" + nullInstructions.id()).json().path("instructions").isNull());
        assertTrue(get(owner, "/v1/exercises/" + emptyInstructions.id()).json().path("instructions").isArray());
        assertEquals(0, get(owner, "/v1/exercises/" + emptyInstructions.id()).json().path("instructions").size());
    }

    @Test
    void workoutAndRoutineReadsPreserveCompleteRootChildAndSetValues() {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Current exercise name", "null");
        ReadRoots roots = createReadRoots(owner, custom.id());

        JsonNode active = get(owner, "/v1/workouts/" + roots.activeWorkout()).json();
        assertEquals(200, get(owner, "/v1/workouts/" + roots.activeWorkout()).status());
        assertEquals(roots.activeWorkout().toString(), active.path("id").asText());
        assertEquals("Active workout", active.path("name").asText());
        assertEquals("active", active.path("status").asText());
        assertEquals(5, active.path("revision").asLong());
        assertEquals("2025-04-05T06:07:08.123Z", active.path("startedAt").asText());
        assertTrue(active.path("finishedAt").isNull());
        assertEquals(1, active.path("exercises").size());
        JsonNode activeEntry = active.path("exercises").get(0);
        assertEquals("active values", activeEntry.path("notes").asText());
        assertEquals(45, activeEntry.path("restDurationSeconds").asInt());
        assertTrue(activeEntry.path("sets").get(0).path("repetitions").isNull());
        assertTrue(activeEntry.path("sets").get(0).path("weightKg").isNull());
        assertFalse(activeEntry.path("sets").get(0).path("isCompleted").asBoolean());
        assertTrue(activeEntry.path("sets").get(0).path("restSeconds").isNull());
        assertEquals(0, activeEntry.path("sets").get(1).path("repetitions").asInt());
        assertEquals(0, activeEntry.path("sets").get(1).path("weightKg").asDouble());
        assertEquals(0, activeEntry.path("sets").get(1).path("restSeconds").asInt());

        Account emptyOwner = account();
        UUID emptyActiveId = UUID.randomUUID();
        jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,?,'active',1)",
                emptyActiveId, emptyOwner.id(), "Empty active", java.sql.Timestamp.from(java.time.Instant.parse("2025-04-08T00:00:00.123Z")));
        JsonNode emptyActive = get(emptyOwner, "/v1/workouts/" + emptyActiveId).json();
        assertEquals("active", emptyActive.path("status").asText());
        assertTrue(emptyActive.path("exercises").isEmpty());

        Reply finishedReply = get(owner, "/v1/workouts/" + roots.finishedWorkout());
        assertEquals(200, finishedReply.status(), new String(finishedReply.bytes(), StandardCharsets.UTF_8));
        JsonNode finished = finishedReply.json();
        assertEquals("Finished history", finished.path("name").asText());
        assertEquals("finished", finished.path("status").asText());
        assertEquals(9, finished.path("revision").asLong());
        assertEquals("2025-04-06T06:07:08.456Z", finished.path("startedAt").asText());
        assertEquals("2025-04-06T07:08:09.789Z", finished.path("finishedAt").asText());
        assertEquals(2, finished.path("exercises").size(), "repeated references remain distinct ordered workout entries");
        JsonNode firstEntry = finished.path("exercises").get(0), secondEntry = finished.path("exercises").get(1);
        assertNotEquals(firstEntry.path("id").asText(), secondEntry.path("id").asText());
        assertEquals(custom.id().toString(), firstEntry.path("exerciseId").asText());
        assertEquals(custom.id().toString(), secondEntry.path("exerciseId").asText());
        assertEquals("Current exercise name", firstEntry.path("exerciseName").asText());
        assertEquals("Current exercise name", secondEntry.path("exerciseName").asText());
        assertEquals(0, firstEntry.path("position").asInt());
        assertEquals("first occurrence", firstEntry.path("notes").asText());
        assertEquals(0, firstEntry.path("restDurationSeconds").asInt());
        assertEquals(0, firstEntry.path("sets").get(0).path("repetitions").asInt());
        assertEquals(0, firstEntry.path("sets").get(0).path("weightKg").asDouble());
        assertTrue(firstEntry.path("sets").get(0).path("restSeconds").isNull());
        assertTrue(firstEntry.path("sets").get(0).path("isCompleted").asBoolean());
        assertEquals(1, secondEntry.path("position").asInt());
        assertEquals("second occurrence", secondEntry.path("notes").asText());
        assertEquals(90, secondEntry.path("restDurationSeconds").asInt());
        assertEquals(2, secondEntry.path("sets").size());
        assertEquals(5, secondEntry.path("sets").get(0).path("repetitions").asInt());
        assertEquals(0, secondEntry.path("sets").get(0).path("weightKg").asDouble());
        assertEquals(0, secondEntry.path("sets").get(0).path("restSeconds").asInt());
        assertEquals(8, secondEntry.path("sets").get(1).path("repetitions").asInt());
        assertEquals(42.5, secondEntry.path("sets").get(1).path("weightKg").asDouble());
        assertEquals(17, secondEntry.path("sets").get(1).path("restSeconds").asInt());

        JsonNode draft = get(owner, "/v1/routines/" + roots.draft()).json();
        assertEquals(200, get(owner, "/v1/routines/" + roots.draft()).status());
        assertTrue(draft.path("isDraft").asBoolean());
        assertEquals("Draft", draft.path("name").asText());
        assertEquals(2, draft.path("revision").asLong());
        assertEquals("Current exercise name", draft.path("exercises").get(0).path("exerciseName").asText());
        assertTrue(draft.path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, draft.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());

        JsonNode saved = get(owner, "/v1/routines/" + roots.saved()).json();
        assertEquals(200, get(owner, "/v1/routines/" + roots.saved()).status());
        assertFalse(saved.path("isDraft").asBoolean());
        assertEquals("Saved routine", saved.path("name").asText());
        assertEquals(4, saved.path("revision").asLong());
        assertEquals("Current exercise name", saved.path("exercises").get(0).path("exerciseName").asText());
        assertEquals(75, saved.path("exercises").get(0).path("restDurationSeconds").asInt());
        assertEquals(0, saved.path("exercises").get(0).path("sets").get(0).path("position").asInt());
        assertEquals(0, saved.path("exercises").get(0).path("sets").get(0).path("repetitions").asInt());
        assertTrue(saved.path("exercises").get(0).path("sets").get(0).path("weightKg").isNull());
        assertFalse(saved.path("exercises").get(0).path("sets").get(0).has("isCompleted"));
        assertFalse(saved.path("exercises").get(0).path("sets").get(0).has("restSeconds"));

        JsonNode blankSaved = get(owner, "/v1/routines/" + roots.blankSaved()).json();
        assertEquals(200, get(owner, "/v1/routines/" + roots.blankSaved()).status());
        assertFalse(blankSaved.path("isDraft").asBoolean());
        assertEquals("", blankSaved.path("name").asText());
        assertTrue(blankSaved.path("exercises").isEmpty(), "temporarily blank/empty saved autosave remains a readable saved routine");
    }

    @Test
    void customRenameRefreshesAllReadLabelsWithoutChangingParentsOrRewritingPriorEvidence() {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Before rename", "[\"original instruction\"]");
        ReadRoots roots = createReadRoots(owner, custom.id());
        String activeBody = "{\"id\":\"" + roots.activeWorkout() + "\",\"name\":\"Active before rename\",\"status\":\"active\",\"startedAt\":\"2025-04-05T06:07:08.123Z\",\"exercises\":[{\"id\":\"" + UUID.randomUUID() + "\",\"exerciseId\":\"" + custom.id() + "\",\"position\":0,\"notes\":\"history value\",\"restDurationSeconds\":73,\"sets\":[{\"id\":\"" + UUID.randomUUID() + "\",\"position\":0,\"repetitions\":8,\"weightKg\":40.5,\"isCompleted\":true,\"restSeconds\":13}]}]}";
        UUID activeKey = UUID.randomUUID();
        Reply activeAccepted = request(owner, "PUT", "/v1/workouts/" + roots.activeWorkout(), activeBody, activeKey.toString(), null);
        assertEquals(200, activeAccepted.status(), new String(activeAccepted.bytes(), StandardCharsets.UTF_8));
        UUID finishKey = UUID.randomUUID();
        String finishBody = "{\"finishedAt\":\"2025-04-06T07:08:09Z\"}";
        Reply finishedAccepted = request(owner, "POST", "/v1/workouts/" + roots.activeWorkout() + "/finish", finishBody, finishKey.toString(), null);
        assertEquals(200, finishedAccepted.status(), new String(finishedAccepted.bytes(), StandardCharsets.UTF_8));

        UUID newActiveId = UUID.randomUUID();
        Reply started = request(owner, "POST", "/v1/workouts", "{\"id\":\"" + newActiveId + "\",\"name\":\"Active root\",\"startedAt\":\"2025-04-07T00:00:00Z\"}", UUID.randomUUID().toString(), null);
        assertEquals(201, started.status(), new String(started.bytes(), StandardCharsets.UTF_8));
        String newActiveBody = "{\"id\":\"" + newActiveId + "\",\"name\":\"Active root\",\"status\":\"active\",\"startedAt\":\"2025-04-07T00:00:00Z\",\"exercises\":[{\"id\":\"" + UUID.randomUUID() + "\",\"exerciseId\":\"" + custom.id() + "\",\"position\":0,\"notes\":\"active domain note\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + UUID.randomUUID() + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        UUID newActiveKey = UUID.randomUUID();
        Reply newActiveAccepted = request(owner, "PUT", "/v1/workouts/" + newActiveId, newActiveBody, newActiveKey.toString(), null);
        assertEquals(200, newActiveAccepted.status(), new String(newActiveAccepted.bytes(), StandardCharsets.UTF_8));

        String draftBody = singleRoutineInput(roots.draft(), "Draft", true, custom.id());
        UUID draftKey = UUID.randomUUID();
        Reply draftAccepted = request(owner, "PUT", "/v1/routines/" + roots.draft(), draftBody, draftKey.toString(), null);
        assertEquals(200, draftAccepted.status(), new String(draftAccepted.bytes(), StandardCharsets.UTF_8));
        String savedBody = singleRoutineInput(roots.saved(), "Saved routine", false, custom.id());
        UUID savedKey = UUID.randomUUID();
        Reply savedAccepted = request(owner, "PUT", "/v1/routines/" + roots.saved(), savedBody, savedKey.toString(), null);
        assertEquals(200, savedAccepted.status(), new String(savedAccepted.bytes(), StandardCharsets.UTF_8));

        JsonNode[] priorResponses = {newActiveAccepted.json(), finishedAccepted.json(), draftAccepted.json(), savedAccepted.json()};
        List<Long> parentRevisions = List.of(
                jdbc.queryForObject("select revision from workouts where id=?", Long.class, newActiveId),
                jdbc.queryForObject("select revision from workouts where id=?", Long.class, roots.activeWorkout()),
                jdbc.queryForObject("select revision from routines where id=?", Long.class, roots.draft()),
                jdbc.queryForObject("select revision from routines where id=?", Long.class, roots.saved()));

        String customBody = "{\"id\":\"" + custom.id() + "\",\"name\":\"After rename\",\"primaryMuscleGroupId\":\"" + custom.muscles().get(2) +
                "\",\"secondaryMuscleGroupIds\":[\"" + custom.muscles().get(0) + "\",\"" + custom.muscles().get(1) + "\"],\"equipmentId\":\"" + custom.equipment() +
                "\",\"equipmentNumItems\":null,\"category\":\"conditioning\",\"difficultyLevel\":\"advanced\",\"forceType\":\"pull\",\"mechanics\":\"compound\",\"instructions\":[\"renamed instruction\"]}";
        Reply rename = request(owner, "PUT", "/v1/exercises/" + custom.id(), customBody, UUID.randomUUID().toString(), null);
        assertEquals(200, rename.status(), new String(rename.bytes(), StandardCharsets.UTF_8));

        List<JsonNode> currentReads = List.of(
                get(owner, "/v1/workouts/" + newActiveId).json(),
                get(owner, "/v1/workouts/" + roots.activeWorkout()).json(),
                get(owner, "/v1/routines/" + roots.draft()).json(),
                get(owner, "/v1/routines/" + roots.saved()).json());
        assertEquals(List.of("active", "finished", "Draft", "Saved routine"), List.of(
                currentReads.get(0).path("status").asText(), currentReads.get(1).path("status").asText(),
                currentReads.get(2).path("name").asText(), currentReads.get(3).path("name").asText()));
        for (int i = 0; i < priorResponses.length; i++) {
            JsonNode expectedDomain = withFreshExerciseName(priorResponses[i], "After rename");
            assertEquals(expectedDomain, currentReads.get(i), "rename changes only the derived label, not parent values or revision");
            assertEquals(parentRevisions.get(i).longValue(), currentReads.get(i).path("revision").asLong());
        }
        assertEquals("After rename", get(owner, "/v1/exercises/" + custom.id()).json().path("name").asText());
        assertEquals("After rename", get(owner, "/v1/workouts/" + roots.finishedWorkout()).json().path("exercises").get(0).path("exerciseName").asText());

        assertArrayEquals(newActiveAccepted.bytes(), request(owner, "PUT", "/v1/workouts/" + newActiveId, newActiveBody, newActiveKey.toString(), null).bytes());
        assertArrayEquals(finishedAccepted.bytes(), request(owner, "POST", "/v1/workouts/" + roots.activeWorkout() + "/finish", finishBody, finishKey.toString(), null).bytes());
        assertArrayEquals(draftAccepted.bytes(), request(owner, "PUT", "/v1/routines/" + roots.draft(), draftBody, draftKey.toString(), null).bytes());
        assertArrayEquals(savedAccepted.bytes(), request(owner, "PUT", "/v1/routines/" + roots.saved(), savedBody, savedKey.toString(), null).bytes());
        assertBatchLabel(owner.id(), "workout", newActiveId, "Before rename");
        assertBatchLabel(owner.id(), "workout", roots.activeWorkout(), "Before rename");
        assertBatchLabel(owner.id(), "routine", roots.draft(), "Before rename");
        assertBatchLabel(owner.id(), "routine", roots.saved(), "Before rename");
    }

    private String singleRoutineInput(UUID root, String name, boolean draft, UUID exercise) {
        return "{\"id\":\"" + root + "\",\"name\":\"" + name + "\",\"isDraft\":" + draft + ",\"exercises\":[{\"id\":\"" + UUID.randomUUID() + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":40,\"sets\":[{\"id\":\"" + UUID.randomUUID() + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
    }

    private JsonNode withFreshExerciseName(JsonNode original, String newName) {
        JsonNode expected = original.deepCopy();
        for (JsonNode entry : expected.path("exercises")) ((com.fasterxml.jackson.databind.node.ObjectNode) entry).put("exerciseName", newName);
        return expected;
    }

    private void assertBatchLabel(UUID accountId, String aggregateType, UUID aggregateId, String expectedName) {
        String payload = jdbc.queryForObject("select payload::text from sync_changes where account_id=? and aggregate_type=? and aggregate_id=? and operation='upsert' order by cursor desc,position desc limit 1",
                String.class, accountId, aggregateType, aggregateId);
        try {
            JsonNode record = JSON.readTree(payload);
            assertEquals(expectedName, record.path("record").path("exercises").get(0).path("exerciseName").asText());
        } catch (Exception e) {
            throw new AssertionError("Stored sync payload is invalid", e);
        }
    }

    @Test
    void canonicalExerciseMapperPreservesTertiaryRoleSupplementNotPinnedHttpEvidence() {
        UUID exerciseId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID muscleId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        ExerciseValue bundledLikeValue = new ExerciseValue(exerciseId, "Synthetic bundled-like entry", "reps", null,
                null, null, null, null, false, null,
                List.of(new ExerciseMuscle(muscleId, "Glutes", "tertiary")),
                List.of(new ExerciseEquipment(UUID.fromString("33333333-3333-4333-8333-333333333333"), "Band", null)));

        var mapped = new CanonicalResourceMapper().exercise(bundledLikeValue);
        assertFalse(mapped.containsKey("revision"));
        List<?> mappedMuscles = (List<?>) mapped.get("muscleGroups");
        Map<?, ?> muscle = (Map<?, ?>) mappedMuscles.get(0);
        assertEquals(muscleId.toString(), muscle.get("id"));
        assertEquals("Glutes", muscle.get("name"));
        assertEquals("tertiary", muscle.get("type"));
        List<?> mappedEquipment = (List<?>) mapped.get("equipment");
        Map<?, ?> equipment = (Map<?, ?>) mappedEquipment.get(0);
        assertEquals("33333333-3333-4333-8333-333333333333", equipment.get("id"));
        assertEquals("Band", equipment.get("name"));
        assertNull(equipment.get("numItems"));
    }

    @Test
    void activeAndFinishedWorkoutStatusWireValuesDoNotDependOnTurkishDefaultLocale() {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Locale exercise", "null");
        ReadRoots roots = createReadRoots(owner, custom.id());
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            JsonNode active = get(owner, "/v1/workouts/" + roots.activeWorkout()).json();
            JsonNode finished = get(owner, "/v1/workouts/" + roots.finishedWorkout()).json();
            assertEquals("active", active.path("status").asText());
            assertEquals("finished", finished.path("status").asText());
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void privateRootsAreIndistinguishableWhenMissingDeletedOrForeignAndAuthRemainsLive() {
        Account owner = account();
        Account ownerSecondSession = session(owner.id(), "1 hour", false);
        Account other = account();
        CustomExercise privateExercise = createCustomExercise(owner.id(), "Private", "null");
        ReadRoots roots = createReadRoots(owner, privateExercise.id());
        UUID deletedExerciseId = createCustomExercise(owner.id(), "Will be deleted", "null").id();
        jdbc.update("update exercises set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", deletedExerciseId);
        UUID deletedWorkoutId = roots.finishedWorkout();
        jdbc.update("update workouts set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", deletedWorkoutId);
        UUID deletedRoutineId = UUID.randomUUID();
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,false,1)", deletedRoutineId, owner.id(), "Will be deleted");
        jdbc.update("update routines set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", deletedRoutineId);

        List<String> routes = List.of("/v1/exercises/", "/v1/workouts/", "/v1/routines/");
        List<UUID> privateIds = List.of(privateExercise.id(), roots.activeWorkout(), roots.draft());
        List<UUID> deletedIds = List.of(deletedExerciseId, deletedWorkoutId, deletedRoutineId);
        for (int i = 0; i < routes.size(); i++) {
            UUID missing = UUID.randomUUID();
            assertNotFound(get(owner, routes.get(i) + missing), missing.toString());
            assertNotFound(get(owner, routes.get(i) + deletedIds.get(i)), deletedIds.get(i).toString());
            assertNotFound(get(other, routes.get(i) + privateIds.get(i)), owner.id().toString());
        }
        assertEquals(200, get(owner, "/v1/exercises/" + BUNDLED_REVERSE_FLY).status());
        assertEquals(200, get(other, "/v1/exercises/" + BUNDLED_REVERSE_FLY).status());

        assertEquals(401, get(null, "/v1/workouts/" + roots.activeWorkout()).status());
        assertAuthFailure(request(null, "GET", "/v1/workouts/" + roots.activeWorkout(), null, null, "not-a-token"));
        Account expired = session(owner.id(), "-1 second", false);
        assertAuthFailure(get(expired, "/v1/workouts/" + roots.activeWorkout()));
        Account revoked = session(owner.id(), "1 hour", true);
        assertAuthFailure(get(revoked, "/v1/workouts/" + roots.activeWorkout()));
        jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", owner.id());
        assertAuthFailure(get(owner, "/v1/workouts/" + roots.activeWorkout()));
        assertAuthFailure(get(ownerSecondSession, "/v1/routines/" + roots.draft()));
        assertEquals(200, get(other, "/v1/exercises/" + BUNDLED_REVERSE_FLY).status());

        UUID logoutAccount = UUID.randomUUID();
        jdbc.update("insert into accounts(id) values(?)", logoutAccount);
        Account loggingOut = session(logoutAccount, "1 hour", false);
        Account stillLive = session(logoutAccount, "1 hour", false);
        CustomExercise sharedExercise = createCustomExercise(logoutAccount, "Still live", "null");
        assertEquals(200, get(stillLive, "/v1/exercises/" + sharedExercise.id()).status());
        Reply logout = request(null, "POST", "/v1/auth/logout", "{\"refreshToken\":\"" + loggingOut.refreshToken() + "\"}", null, null);
        assertEquals(204, logout.status(), new String(logout.bytes(), StandardCharsets.UTF_8));
        assertEquals(0, logout.bytes().length, "accepted logout is a bodyless 204");
        assertAuthFailure(get(loggingOut, "/v1/exercises/" + sharedExercise.id()));
        assertEquals(200, get(stillLive, "/v1/exercises/" + sharedExercise.id()).status(), "revoking one session must not revoke its account's other live session");
    }

    @Test
    void readRequestValidationAndOptionalIdempotencyKeyDoNotClaimEvidenceOrMutateState() {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Read only", "[]");
        ReadRoots roots = createReadRoots(owner, custom.id());
        String before = readSideEffectSnapshot(owner.id(), roots);

        Reply uppercaseUuid = get(owner, "/v1/exercises/" + custom.id().toString().toUpperCase());
        assertError(uppercaseUuid, 422, "validation_error");
        assertError(get(owner, "/v1/workouts/not-a-uuid"), 422, "validation_error");
        assertError(get(owner, "/v1/routines/" + roots.saved() + "?unsupported=1"), 400, "invalid_request");
        assertError(request(owner, "GET", "/v1/exercises/" + custom.id(), "{}", null, null), 422, "validation_error");
        Reply withKey = request(owner, "GET", "/v1/exercises/" + custom.id(), null, UUID.randomUUID().toString(), null);
        assertEquals(200, withKey.status(), new String(withKey.bytes(), StandardCharsets.UTF_8));
        assertEquals(200, get(owner, "/v1/exercises/" + custom.id()).status(), "repeating a GET remains a fresh read, not an operation replay");
        Account foreignOwner = account();
        CustomExercise foreignExercise = createCustomExercise(foreignOwner.id(), "Foreign", "null");
        assertNotFound(get(owner, "/v1/exercises/" + foreignExercise.id()), foreignOwner.id().toString());
        assertAuthFailure(get(null, "/v1/exercises/" + custom.id()));
        assertEquals(before, readSideEffectSnapshot(owner.id(), roots));

        Reply missing = get(owner, "/v1/routines/" + UUID.randomUUID());
        assertError(missing, 404, "not_found");
        assertEquals(before, readSideEffectSnapshot(owner.id(), roots));
    }

    @Test
    void workoutReadKeepsRootAndChildrenFromTheSnapshotEstablishedBeforeWholeRootReplacement() throws Exception {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Stable exercise label", "null");
        ReadRoots roots = createReadRoots(owner, custom.id());
        UUID oldEntry = jdbc.queryForObject("select id from workout_exercises where workout_id=? order by position limit 1", UUID.class, roots.activeWorkout());
        ReadGate gate = new ReadGate(roots.activeWorkout(), "from workouts", null);
        ACTIVE_READ_GATE.set(gate);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingRead = executor.submit(() -> get(owner, "/v1/workouts/" + roots.activeWorkout()));
            assertTrue(gate.rootQueryReturned.await(10, TimeUnit.SECONDS), "real HTTP read must complete its root SELECT before the writer starts");
            UUID replacementEntry = UUID.randomUUID(), replacementSet = UUID.randomUUID();
            String body = "{\"id\":\"" + roots.activeWorkout() + "\",\"name\":\"Committed replacement\",\"status\":\"active\",\"startedAt\":\"2025-04-05T06:07:08.123Z\",\"exercises\":[{" +
                    "\"id\":\"" + replacementEntry + "\",\"exerciseId\":\"" + custom.id() + "\",\"position\":0,\"notes\":\"new child view\",\"restDurationSeconds\":12,\"sets\":[{\"id\":\"" + replacementSet + "\",\"position\":0,\"repetitions\":4,\"weightKg\":6.5,\"isCompleted\":false,\"restSeconds\":null}]}]}";
            Reply replacement = request(owner, "PUT", "/v1/workouts/" + roots.activeWorkout(), body, UUID.randomUUID().toString(), null);
            assertEquals(200, replacement.status(), new String(replacement.bytes(), StandardCharsets.UTF_8));
            String renameBody = "{\"id\":\"" + custom.id() + "\",\"name\":\"Renamed during read\",\"primaryMuscleGroupId\":\"" + custom.muscles().get(2) +
                    "\",\"secondaryMuscleGroupIds\":[\"" + custom.muscles().get(0) + "\",\"" + custom.muscles().get(1) + "\"],\"equipmentId\":\"" + custom.equipment() +
                    "\",\"equipmentNumItems\":null}";
            Reply rename = request(owner, "PUT", "/v1/exercises/" + custom.id(), renameBody, UUID.randomUUID().toString(), null);
            assertEquals(200, rename.status(), new String(rename.bytes(), StandardCharsets.UTF_8));
            gate.continueRead.countDown();

            Reply beforeCommitView = pendingRead.get(10, TimeUnit.SECONDS);
            assertEquals(200, beforeCommitView.status(), new String(beforeCommitView.bytes(), StandardCharsets.UTF_8));
            JsonNode oldView = beforeCommitView.json();
            assertEquals("Active workout", oldView.path("name").asText());
            assertEquals(5, oldView.path("revision").asLong());
            assertEquals(oldEntry.toString(), oldView.path("exercises").get(0).path("id").asText());
            assertEquals("active values", oldView.path("exercises").get(0).path("notes").asText());
            assertEquals("Stable exercise label", oldView.path("exercises").get(0).path("exerciseName").asText());

            JsonNode afterCommitView = get(owner, "/v1/workouts/" + roots.activeWorkout()).json();
            assertEquals("Committed replacement", afterCommitView.path("name").asText());
            assertEquals(6, afterCommitView.path("revision").asLong());
            assertEquals(replacementEntry.toString(), afterCommitView.path("exercises").get(0).path("id").asText());
            assertEquals("new child view", afterCommitView.path("exercises").get(0).path("notes").asText());
            assertEquals("Renamed during read", afterCommitView.path("exercises").get(0).path("exerciseName").asText(),
                    "fresh parent reads resolve the renamed label after the concurrent commit");
        } finally {
            gate.continueRead.countDown();
            ACTIVE_READ_GATE.compareAndSet(gate, null);
            executor.shutdownNow();
        }
    }

    @Test
    void routineReadKeepsRootAndChildrenFromTheSnapshotEstablishedBeforeWholeRootReplacement() throws Exception {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Stable routine label", "null");
        ReadRoots roots = createReadRoots(owner, custom.id());
        UUID oldEntry = jdbc.queryForObject("select id from routine_exercises where routine_id=?", UUID.class, roots.saved());
        ReadGate gate = new ReadGate(roots.saved(), "from routines", null);
        ACTIVE_READ_GATE.set(gate);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingRead = executor.submit(() -> get(owner, "/v1/routines/" + roots.saved()));
            assertTrue(gate.rootQueryReturned.await(10, TimeUnit.SECONDS), "real HTTP read must complete its root SELECT before the writer starts");
            UUID replacementEntry = UUID.randomUUID(), replacementSet = UUID.randomUUID();
            String body = "{\"id\":\"" + roots.saved() + "\",\"name\":\"Saved replacement\",\"isDraft\":false,\"exercises\":[{" +
                    "\"id\":\"" + replacementEntry + "\",\"exerciseId\":\"" + custom.id() + "\",\"position\":0,\"restDurationSeconds\":21,\"sets\":[{\"id\":\"" + replacementSet + "\",\"position\":0,\"repetitions\":7,\"weightKg\":8.5}]}]}";
            Reply replacement = request(owner, "PUT", "/v1/routines/" + roots.saved(), body, UUID.randomUUID().toString(), null);
            assertEquals(200, replacement.status(), new String(replacement.bytes(), StandardCharsets.UTF_8));
            gate.continueRead.countDown();

            Reply beforeCommitView = pendingRead.get(10, TimeUnit.SECONDS);
            assertEquals(200, beforeCommitView.status(), new String(beforeCommitView.bytes(), StandardCharsets.UTF_8));
            JsonNode oldView = beforeCommitView.json();
            assertEquals("Saved routine", oldView.path("name").asText());
            assertEquals(4, oldView.path("revision").asLong());
            assertEquals(oldEntry.toString(), oldView.path("exercises").get(0).path("id").asText());
            assertEquals("Stable routine label", oldView.path("exercises").get(0).path("exerciseName").asText());

            JsonNode afterCommitView = get(owner, "/v1/routines/" + roots.saved()).json();
            assertEquals("Saved replacement", afterCommitView.path("name").asText());
            assertEquals(5, afterCommitView.path("revision").asLong());
            assertEquals(replacementEntry.toString(), afterCommitView.path("exercises").get(0).path("id").asText());
        } finally {
            gate.continueRead.countDown();
            ACTIVE_READ_GATE.compareAndSet(gate, null);
            executor.shutdownNow();
        }
    }

    @Test
    void exerciseReadKeepsMetadataAndRelationshipsFromOneViewDuringRenameAndRelationshipReplacement() throws Exception {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "Before rename", "[\"old\"]");
        UUID alternateMuscle = jdbc.queryForObject("select id from muscle_groups where id not in (?,?,?) order by id limit 1", UUID.class,
                custom.muscles().get(0), custom.muscles().get(1), custom.muscles().get(2));
        UUID alternateEquipment = jdbc.queryForObject("select id from equipment where id<>? order by id limit 1", UUID.class, custom.equipment());
        ReadGate gate = new ReadGate(custom.id(), "from exercises", null);
        ACTIVE_READ_GATE.set(gate);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingRead = executor.submit(() -> get(owner, "/v1/exercises/" + custom.id()));
            assertTrue(gate.rootQueryReturned.await(10, TimeUnit.SECONDS), "real HTTP read must complete its exercise SELECT before the writer starts");
            String renameBody = "{\"id\":\"" + custom.id() + "\",\"name\":\"After rename\",\"primaryMuscleGroupId\":\"" + alternateMuscle +
                    "\",\"secondaryMuscleGroupIds\":[],\"equipmentId\":\"" + alternateEquipment + "\",\"equipmentNumItems\":2,\"category\":\"new category\",\"difficultyLevel\":\"beginner\",\"forceType\":\"push\",\"mechanics\":\"compound\",\"instructions\":[\"new\"]}";
            Reply rename = request(owner, "PUT", "/v1/exercises/" + custom.id(), renameBody, UUID.randomUUID().toString(), null);
            assertEquals(200, rename.status(), new String(rename.bytes(), StandardCharsets.UTF_8));
            gate.continueRead.countDown();

            Reply beforeCommitView = pendingRead.get(10, TimeUnit.SECONDS);
            assertEquals(200, beforeCommitView.status(), new String(beforeCommitView.bytes(), StandardCharsets.UTF_8));
            JsonNode oldView = beforeCommitView.json();
            assertEquals("Before rename", oldView.path("name").asText());
            assertEquals("conditioning", oldView.path("category").asText());
            assertEquals(List.of("old"), JSON.convertValue(oldView.path("instructions"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertEquals(custom.muscles().get(2).toString(), oldView.path("muscleGroups").get(0).path("id").asText());
            assertEquals(custom.equipment().toString(), oldView.path("equipment").get(0).path("id").asText());
            assertTrue(oldView.path("equipment").get(0).path("numItems").isNull());

            JsonNode afterCommitView = get(owner, "/v1/exercises/" + custom.id()).json();
            assertEquals("After rename", afterCommitView.path("name").asText());
            assertEquals("new category", afterCommitView.path("category").asText());
            assertEquals(alternateMuscle.toString(), afterCommitView.path("muscleGroups").get(0).path("id").asText());
            assertEquals(alternateEquipment.toString(), afterCommitView.path("equipment").get(0).path("id").asText());
            assertEquals(2, afterCommitView.path("equipment").get(0).path("numItems").asInt());
        } finally {
            gate.continueRead.countDown();
            ACTIVE_READ_GATE.compareAndSet(gate, null);
            executor.shutdownNow();
        }
    }

    @Test
    void transientAndUnexpectedReadFailuresMapSafelyAndRetryWithoutChangingState() {
        Account owner = account();
        CustomExercise custom = createCustomExercise(owner.id(), "No side effect", "null");
        ReadRoots roots = createReadRoots(owner, custom.id());
        String before = readSideEffectSnapshot(owner.id(), roots);

        List<ReadFailure> failures = List.of(
                new ReadFailure(new org.springframework.dao.DataAccessResourceFailureException("test transient", new java.sql.SQLTransientException("transient without SQLState")), 503),
                new ReadFailure(new org.springframework.dao.DataAccessResourceFailureException("test serialization", new java.sql.SQLException("serialization failure", "40001")), 503),
                new ReadFailure(new org.springframework.dao.DataAccessResourceFailureException("test unexpected persistence", new IllegalStateException("unexpected database adapter failure")), 500));
        for (ReadFailure failure : failures) {
            ACTIVE_READ_GATE.set(new ReadGate(roots.activeWorkout(), "from workouts", failure.cause()));
            Reply failed = get(owner, "/v1/workouts/" + roots.activeWorkout());
            assertError(failed, failure.expectedStatus(), failure.expectedStatus() == 503 ? "temporarily_unavailable" : "internal_error");
            assertFalse(new String(failed.bytes(), StandardCharsets.UTF_8).contains("test "));
            assertEquals(before, readSideEffectSnapshot(owner.id(), roots), "failed GET must not create domain or replay evidence");
            ACTIVE_READ_GATE.set(null);
            Reply retry = get(owner, "/v1/workouts/" + roots.activeWorkout());
            assertEquals(200, retry.status(), new String(retry.bytes(), StandardCharsets.UTF_8));
            assertEquals("Active workout", retry.json().path("name").asText());
            assertEquals(before, readSideEffectSnapshot(owner.id(), roots));
        }
    }

    private record ReadFailure(RuntimeException cause, int expectedStatus) {}

    private String readSideEffectSnapshot(UUID owner, ReadRoots roots) {
        String query = "select jsonb_build_object(" +
                "'exercise',(select to_jsonb(e) from exercises e where e.id=?)," +
                "'exerciseMuscles',(select jsonb_agg(to_jsonb(m) order by m.type,m.muscle_group_id) from exercise_muscle_groups m where m.exercise_id=?)," +
                "'exerciseEquipment',(select jsonb_agg(to_jsonb(equip) order by equip.equipment_id) from exercise_equipment equip where equip.exercise_id=?)," +
                "'workouts',(select jsonb_agg(to_jsonb(w) order by w.id) from workouts w where w.owner_id=?)," +
                "'workoutEntries',(select jsonb_agg(to_jsonb(e) order by e.workout_id,e.position) from workout_exercises e join workouts w on w.id=e.workout_id where w.owner_id=?)," +
                "'workoutSets',(select jsonb_agg(to_jsonb(s) order by e.workout_id,e.position,s.position) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id join workouts w on w.id=e.workout_id where w.owner_id=?)," +
                "'routines',(select jsonb_agg(to_jsonb(r) order by r.id) from routines r where r.owner_id=?)," +
                "'routineEntries',(select jsonb_agg(to_jsonb(e) order by e.routine_id,e.position) from routine_exercises e join routines r on r.id=e.routine_id where r.owner_id=?)," +
                "'routineSets',(select jsonb_agg(to_jsonb(s) order by e.routine_id,e.position,s.position) from routine_sets s join routine_exercises e on e.id=s.routine_exercise_id join routines r on r.id=e.routine_id where r.owner_id=?)," +
                "'cursor',(select to_jsonb(s) from account_sync_state s where s.account_id=?)," +
                "'batches',(select jsonb_agg(to_jsonb(b) order by b.cursor) from sync_change_batches b where b.account_id=?)," +
                "'changes',(select jsonb_agg(to_jsonb(c) order by c.cursor,c.position) from sync_changes c where c.account_id=?)," +
                "'resourceEvidence',(select jsonb_agg(to_jsonb(m) order by m.idempotency_key) from resource_mutations m where m.owner_id=?)," +
                "'syncEvidence',(select jsonb_agg(to_jsonb(m) order by m.mutation_id) from processed_mutations m where m.owner_id=?)," +
                "'rootReservations',(select jsonb_agg(to_jsonb(r) order by r.aggregate_type,r.aggregate_id) from root_id_reservations r where r.owner_id=?)," +
                "'childReservations',(select jsonb_agg(to_jsonb(r) order by r.family,r.child_id) from child_id_reservations r where r.owner_id=?)," +
                "'credentials',(select jsonb_agg(jsonb_build_object('fingerprint',encode(c.fingerprint,'hex'),'expires',c.expires_at) order by c.fingerprint) from auth_credentials c join auth_sessions s on s.id=c.session_id where s.account_id=?))::text";
        return jdbc.queryForObject(query, String.class, roots.exercise(), roots.exercise(), roots.exercise(),
                owner, owner, owner, owner, owner, owner, owner, owner, owner, owner, owner, owner, owner, owner);
    }

    private void assertNotFound(Reply reply, String forbiddenValue) {
        assertError(reply, 404, "not_found");
        String body = new String(reply.bytes(), StandardCharsets.UTF_8);
        if (forbiddenValue != null) assertFalse(body.contains(forbiddenValue), "not-found body must not reveal a private identifier");
    }

    private void assertAuthFailure(Reply reply) {
        assertError(reply, 401, "authentication_required");
    }

    private void assertError(Reply reply, int status, String code) {
        assertEquals(status, reply.status(), new String(reply.bytes(), StandardCharsets.UTF_8));
        JsonNode body = reply.json();
        assertEquals(code, body.path("errorCode").asText());
        assertTrue(body.size() <= 2, "error body must not expose implementation or account data");
        assertFalse(body.toString().contains("org.postgresql"));
        assertFalse(body.toString().contains("stackTrace"));
    }
}
