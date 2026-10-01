package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrowsingHttpIntegrationTest {
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String BUNDLED_EXERCISE = "960b59d9-e989-4bf1-ad45-37f1e0868de2";
    private static final String SHOULDERS = "5181c3d4-3cfd-4e9e-9ae3-3de472e084bc";
    private static final String MIDDLE_BACK = "cf81b51f-97d4-4cb2-ab3c-1d59472481ed";
    private static final String DUMBBELL = "22ad09f6-c689-4096-ad05-971ca8a6f0aa";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<RoutineReadGate> ACTIVE_ROUTINE_READ_GATE = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "browsing-integration-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;

    @MockitoSpyBean
    JdbcTemplate jdbc;

    @Autowired
    AuthCrypto crypto;

    @MockitoBean
    Clock serviceClock;

    @BeforeEach
    void setDefaultListClock() {
        when(serviceClock.instant()).thenReturn(Instant.now());
        ACTIVE_ROUTINE_READ_GATE.set(null);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            RoutineReadGate gate = ACTIVE_ROUTINE_READ_GATE.get();
            if (gate != null && gate.matches(invocation.getArgument(0), invocation.getArguments()))
                gate.afterRootQuery();
            return result;
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    private static final class RoutineReadGate {
        final UUID routineId;
        final CountDownLatch rootQueryReturned = new CountDownLatch(1);
        final CountDownLatch continueRead = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();

        RoutineReadGate(UUID routineId) { this.routineId = routineId; }

        boolean matches(String sql, Object[] invocationArguments) {
            if (!sql.toLowerCase(java.util.Locale.ROOT).contains("select id,name,is_draft,revision from routines where id=?")) return false;
            boolean targetFound = false;
            for (int index = 2; index < invocationArguments.length; index++) {
                Object argument = invocationArguments[index];
                if (routineId.equals(argument)) targetFound = true;
                if (argument instanceof Object[] expandedArguments && java.util.Arrays.asList(expandedArguments).contains(routineId))
                    targetFound = true;
            }
            return targetFound && used.compareAndSet(false, true);
        }

        void afterRootQuery() {
            rootQueryReturned.countDown();
            try {
                if (!continueRead.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("Routine list read gate was not released in time");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Routine list read gate interrupted", exception);
            }
        }
    }

    private record Account(UUID id, String accessToken, String refreshToken) {}
    private record Reply(int status, byte[] bytes) {
        JsonNode json() {
            try {
                return JSON.readTree(bytes);
            } catch (Exception exception) {
                throw new AssertionError("Expected JSON response", exception);
            }
        }
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    private Account account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into accounts(id) values(?)", id);
        return session(id);
    }

    private Account session(UUID accountId) {
        UUID session = UUID.randomUUID();
        String access = UUID.randomUUID().toString();
        String refresh = UUID.randomUUID().toString();
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", session, accountId);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '1 hour'),(?,?, 'refresh',clock_timestamp(),clock_timestamp()+interval '30 days')",
                crypto.digest(access), session, crypto.digest(refresh), session);
        return new Account(accountId, access, refresh);
    }

    private Reply get(Account account, String path) {
        return getWithKey(account, path, null);
    }

    private Reply getWithKey(Account account, String path, String idempotencyKey) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (account != null) request.header("Authorization", "Bearer " + account.accessToken());
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception exception) {
            throw new AssertionError("GET failed: " + path, exception);
        }
    }

    private Reply logout(String refreshToken) {
        try {
            String body = "{\"refreshToken\":\"" + refreshToken + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/logout"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception exception) {
            throw new AssertionError("Logout request failed", exception);
        }
    }

    private Reply mutate(Account account, String method, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception exception) {
            throw new AssertionError("Mutation failed: " + method + " " + path, exception);
        }
    }

    private Reply getAt(int targetPort, Account account, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception exception) {
            throw new AssertionError("GET failed after service restart: " + path, exception);
        }
    }

    private Process startBrowseProcess(String java, String classpath, int childPort) {
        try {
            ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                    "com.dragosfotache.gtbackend.GtBackendApplication", "--server.port=" + childPort);
            Map<String, String> environment = builder.environment();
            environment.put("DB_URL", POSTGRES.getJdbcUrl());
            environment.put("DB_USER", POSTGRES.getUsername());
            environment.put("DB_PASSWORD", POSTGRES.getPassword());
            environment.put("AUTH_ENCRYPTION_KEY", ENCRYPTION_KEY);
            environment.put("GOOGLE_OAUTH_AUDIENCE", "browsing-integration-audience");
            environment.put("AUTH_CLEANUP_INITIAL_DELAY_MS", "3600000");
            environment.put("AUTH_CLEANUP_FIXED_DELAY_MS", "3600000");
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        } catch (Exception exception) {
            throw new AssertionError("Unable to start isolated browsing JVM", exception);
        }
    }

    private void awaitBrowseProcess(int childPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + childPort + "/v1/catalogue/exercises"))
                        .GET().build();
                if (HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 401) return;
            } catch (Exception ignored) {
                // The isolated process has not bound its HTTP port yet.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Isolated browsing JVM did not become ready");
    }

    private void stopBrowseProcess(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "test-owned browsing JVM must terminate");
        }
    }

    private UUID customExercise(UUID owner, String id, String name, String instructions) {
        UUID exercise = UUID.fromString(id);
        jdbc.update("insert into exercises(id,owner_id,is_user_created,name,type,instructions,created_at,updated_at,revision) values(?,?,true,?,'reps',?::jsonb,clock_timestamp(),clock_timestamp(),1)",
                exercise, owner, name, instructions);
        return exercise;
    }

    private UUID customExercise(UUID owner, String name) {
        return customExercise(owner, UUID.randomUUID().toString(), name, "null");
    }

    private void addExerciseMuscle(UUID exercise, String muscle, String role) {
        jdbc.update("insert into exercise_muscle_groups(exercise_id,muscle_group_id,type) values(?,?,?)",
                exercise, UUID.fromString(muscle), role);
    }

    private void addExerciseEquipment(UUID exercise, String equipment, Integer count) {
        jdbc.update("insert into exercise_equipment(exercise_id,equipment_id,num_items) values(?,?,?)",
                exercise, UUID.fromString(equipment), count);
    }

    private void finishedWorkout(UUID owner, String id, String name, Instant startedAt, Instant finishedAt, long revision) {
        UUID workout = UUID.fromString(id);
        UUID bundledExercise = UUID.fromString(BUNDLED_EXERCISE);
        UUID entry = UUID.randomUUID();
        jdbc.update("insert into workouts(id,owner_id,name,started_at,finished_at,status,revision) values(?,?,?,?,?,'finished',?)",
                workout, owner, name, Timestamp.from(startedAt), Timestamp.from(finishedAt), revision);
        jdbc.update("insert into workout_exercises(id,workout_id,exercise_id,position,notes,rest_duration_seconds) values(?,?,?,0,'',30)",
                entry, workout, bundledExercise);
        jdbc.update("insert into workout_sets(id,workout_exercise_id,position,repetitions,weight_kg,is_completed) values(?,?,0,1,1,true)",
                UUID.randomUUID(), entry);
    }

    private void activeWorkout(UUID owner, String id, String name, Instant startedAt, long revision) {
        jdbc.update("insert into workouts(id,owner_id,name,started_at,status,revision) values(?,?,?,?,'active',?)",
                UUID.fromString(id), owner, name, Timestamp.from(startedAt), revision);
    }

    private String completedWorkoutInput(String root, UUID entry, UUID set) {
        return "{\"id\":\"" + root + "\",\"name\":\"will finish\",\"status\":\"active\",\"startedAt\":\"2025-03-01T00:00:00.123Z\",\"exercises\":[{" +
                "\"id\":\"" + entry + "\",\"exerciseId\":\"" + BUNDLED_EXERCISE + "\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{" +
                "\"id\":\"" + set + "\",\"position\":0,\"repetitions\":5,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":null}]}]}";
    }

    private void savedRoutine(UUID owner, String id, String name, long revision) {
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,false,?)",
                UUID.fromString(id), owner, name, revision);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String accountState(UUID owner) {
        String id = owner.toString();
        String sql = "select jsonb_build_object(" +
                "'workouts',(select jsonb_agg(to_jsonb(w) order by w.id) from workouts w where w.owner_id='" + id + "'::uuid)," +
                "'workoutEntries',(select jsonb_agg(to_jsonb(e) order by e.workout_id,e.position) from workout_exercises e join workouts w on w.id=e.workout_id where w.owner_id='" + id + "'::uuid)," +
                "'workoutSets',(select jsonb_agg(to_jsonb(s) order by e.workout_id,e.position,s.position) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id join workouts w on w.id=e.workout_id where w.owner_id='" + id + "'::uuid)," +
                "'routines',(select jsonb_agg(to_jsonb(r) order by r.id) from routines r where r.owner_id='" + id + "'::uuid)," +
                "'routineEntries',(select jsonb_agg(to_jsonb(e) order by e.routine_id,e.position) from routine_exercises e join routines r on r.id=e.routine_id where r.owner_id='" + id + "'::uuid)," +
                "'routineSets',(select jsonb_agg(to_jsonb(s) order by e.routine_id,e.position,s.position) from routine_sets s join routine_exercises e on e.id=s.routine_exercise_id join routines r on r.id=e.routine_id where r.owner_id='" + id + "'::uuid)," +
                "'customExercises',(select jsonb_agg(to_jsonb(e) order by e.id) from exercises e where e.owner_id='" + id + "'::uuid)," +
                "'syncState',(select to_jsonb(s) from account_sync_state s where s.account_id='" + id + "'::uuid)," +
                "'batches',(select jsonb_agg(to_jsonb(b) order by b.cursor) from sync_change_batches b where b.account_id='" + id + "'::uuid)," +
                "'changes',(select jsonb_agg(to_jsonb(c) order by c.cursor,c.position) from sync_changes c where c.account_id='" + id + "'::uuid)," +
                "'resourceEvidence',(select jsonb_agg(to_jsonb(m) order by m.idempotency_key) from resource_mutations m where m.owner_id='" + id + "'::uuid)," +
                "'syncEvidence',(select jsonb_agg(to_jsonb(m) order by m.mutation_id) from processed_mutations m where m.owner_id='" + id + "'::uuid)," +
                "'rootReservations',(select jsonb_agg(to_jsonb(r) order by r.aggregate_type,r.aggregate_id) from root_id_reservations r where r.owner_id='" + id + "'::uuid)," +
                "'childReservations',(select jsonb_agg(to_jsonb(r) order by r.family,r.child_id) from child_id_reservations r where r.owner_id='" + id + "'::uuid)," +
                "'credentials',(select jsonb_agg(jsonb_build_object('fingerprint',encode(c.fingerprint,'hex'),'expires',c.expires_at) order by c.fingerprint) from auth_credentials c join auth_sessions s on s.id=c.session_id where s.account_id='" + id + "'::uuid))::text";
        return jdbc.queryForObject(sql, String.class);
    }

    private List<String> itemIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private JsonNode item(JsonNode page, UUID id) {
        for (JsonNode candidate : page.path("items")) {
            if (id.toString().equals(candidate.path("id").asText())) return candidate;
        }
        throw new AssertionError("Missing expected list item " + id);
    }

    private List<JsonNode> allItems(Account account, String initialPath) {
        List<JsonNode> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenTokens = new HashSet<>();
        String path = initialPath;
        while (true) {
            Reply reply = get(account, path);
            assertEquals(200, reply.status(), reply.text());
            JsonNode page = reply.json();
            assertEquals("gymtracka-catalogue-v1", page.path("catalogueVersion").asText());
            assertPageEnvelope(page, page.path("hasMore").asBoolean());
            for (JsonNode candidate : page.path("items")) {
                String id = candidate.path("id").asText();
                assertTrue(seenIds.add(id), "keyset traversal must not duplicate " + id);
                items.add(candidate);
            }
            if (!page.path("hasMore").asBoolean()) return items;
            String token = page.path("nextPageToken").asText();
            assertFalse(token.isEmpty(), "hasMore requires a continuation token");
            assertTrue(seenTokens.add(token), "keyset traversal must progress to a new token");
            path = initialPath + (initialPath.contains("?") ? "&" : "?") + "pageToken=" + encode(token);
        }
    }

    private List<String> ids(List<JsonNode> items) {
        return items.stream().map(item -> item.path("id").asText()).toList();
    }

    private JsonNode item(List<JsonNode> items, UUID id) {
        return items.stream().filter(candidate -> id.toString().equals(candidate.path("id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing expected list item " + id));
    }

    private void assertPageEnvelope(JsonNode page, boolean hasMore) {
        assertEquals(hasMore, page.path("hasMore").asBoolean());
        if (hasMore) assertTrue(page.path("nextPageToken").isTextual() && !page.path("nextPageToken").asText().isEmpty());
        else assertTrue(page.path("nextPageToken").isMissingNode() || page.path("nextPageToken").isNull());
    }

    @Test
    void exerciseSearchCoversBundledCustomUnicodeLiteralTextAndBooleanFilters() {
        Account owner = account();
        Account other = account();
        UUID custom = customExercise(owner.id(), UUID.randomUUID().toString(), "İstanbul 100%_\\escape", "[]");
        addExerciseMuscle(custom, SHOULDERS, "primary");
        addExerciseMuscle(custom, MIDDLE_BACK, "secondary");
        addExerciseEquipment(custom, DUMBBELL, 0);
        UUID foreignCustom = customExercise(other.id(), "foreign only");
        UUID whitespaceCustom = customExercise(owner.id(), "qa-white-space-marker beta");

        String equipmentSearch = "/v1/catalogue/exercises?q=" + encode("dUmBbElL") + "&limit=200";
        List<JsonNode> bundleItems = allItems(owner, equipmentSearch);
        List<String> bundleIds = ids(bundleItems);
        assertTrue(bundleIds.contains(BUNDLED_EXERCISE), "equipment-name search includes the pinned bundled record");
        assertTrue(bundleIds.contains(custom.toString()), "equipment-name search includes owned custom references");
        assertFalse(bundleIds.contains(foreignCustom.toString()));
        JsonNode listedCustom = item(bundleItems, custom);
        assertEquals("İstanbul 100%_\\escape", listedCustom.path("name").asText());
        assertEquals("reps", listedCustom.path("type").asText());
        assertTrue(listedCustom.path("isUserCreated").asBoolean());
        assertEquals(1, listedCustom.path("revision").asLong());
        assertTrue(listedCustom.path("category").isNull());
        assertTrue(listedCustom.path("difficultyLevel").isNull());
        assertTrue(listedCustom.path("forceType").isNull());
        assertTrue(listedCustom.path("mechanics").isNull());
        assertTrue(listedCustom.path("instructions").isEmpty());
        assertEquals(SHOULDERS, listedCustom.path("muscleGroups").get(0).path("id").asText());
        assertEquals("primary", listedCustom.path("muscleGroups").get(0).path("type").asText());
        assertEquals("Shoulders", listedCustom.path("muscleGroups").get(0).path("name").asText());
        assertEquals("secondary", listedCustom.path("muscleGroups").get(1).path("type").asText());
        assertEquals("Middle Back", listedCustom.path("muscleGroups").get(1).path("name").asText());
        assertEquals(DUMBBELL, listedCustom.path("equipment").get(0).path("id").asText());
        assertEquals("Dumbbell", listedCustom.path("equipment").get(0).path("name").asText());
        assertEquals(0, listedCustom.path("equipment").get(0).path("numItems").asInt());
        JsonNode listedBundle = item(bundleItems, UUID.fromString(BUNDLED_EXERCISE));
        assertEquals("Dumbbell Reverse Fly", listedBundle.path("name").asText());
        assertEquals("reps", listedBundle.path("type").asText());
        assertFalse(listedBundle.path("isUserCreated").asBoolean());
        assertFalse(listedBundle.has("revision"));
        assertEquals("intermediate", listedBundle.path("difficultyLevel").asText());
        assertEquals("pull", listedBundle.path("forceType").asText());
        assertEquals("isolation", listedBundle.path("mechanics").asText());
        assertEquals(2, listedBundle.path("muscleGroups").size());
        assertEquals(List.of("primary", "primary"), listedBundle.path("muscleGroups").findValuesAsText("type"));
        assertEquals(List.of("Shoulders", "Middle Back"), listedBundle.path("muscleGroups").findValuesAsText("name"));
        assertEquals("Dumbbell", listedBundle.path("equipment").get(0).path("name").asText());
        assertTrue(listedBundle.path("equipment").get(0).path("numItems").isNull());

        List<String> muscleSearch = ids(allItems(owner, "/v1/catalogue/exercises?q=" + encode("sHoUlDeRs") + "&limit=200"));
        assertTrue(muscleSearch.contains(custom.toString()), "muscle names participate in search");
        assertTrue(muscleSearch.contains(BUNDLED_EXERCISE));

        List<String> unicode = ids(allItems(owner, "/v1/catalogue/exercises?q=" + encode("i\u0307stanbul 100%_\\escape") + "&limit=200"));
        assertEquals(List.of(custom.toString()), unicode, "Unicode lowercase is locale-independent and SQL metacharacters are literal");
        assertEquals(List.of(whitespaceCustom.toString()), ids(allItems(owner, "/v1/catalogue/exercises?q=qa-white&limit=200")));
        assertTrue(allItems(owner, "/v1/catalogue/exercises?q=" + encode(" qa-white") + "&limit=200").isEmpty(),
                "q whitespace is preserved rather than trimmed");
        JsonNode emptyQuery = get(owner, "/v1/catalogue/exercises?q=").json();
        JsonNode noQuery = get(owner, "/v1/catalogue/exercises").json();
        assertEquals(itemIds(noQuery), itemIds(emptyQuery), "absent and empty q have the same filtering semantics");
        assertFalse(itemIds(get(owner, "/v1/catalogue/exercises?q=foreign%20only&limit=200").json()).contains(foreignCustom.toString()),
                "another account's custom exercise is never visible");

        String onlyEquipment = "/v1/catalogue/exercises?equipmentId=" + DUMBBELL + "&limit=200";
        String onlyShoulders = "/v1/catalogue/exercises?muscleGroupId=" + SHOULDERS + "&limit=200";
        String combined = onlyEquipment + "&muscleGroupId=" + SHOULDERS;
        assertTrue(ids(allItems(owner, onlyEquipment)).contains(custom.toString()));
        assertTrue(ids(allItems(owner, onlyShoulders)).contains(custom.toString()));
        assertTrue(ids(allItems(owner, combined)).contains(custom.toString()), "filter categories combine with AND");
        assertTrue(allItems(owner, "/v1/catalogue/exercises?muscleGroupId=" + SHOULDERS + "&equipmentId=" + UUID.randomUUID() + "&limit=200").isEmpty(),
                "a match in one category cannot satisfy a nonmatching second category");
        assertTrue(ids(allItems(owner, "/v1/catalogue/exercises?muscleGroupId=" + SHOULDERS + "&muscleGroupId=" + MIDDLE_BACK + "&limit=200")).contains(BUNDLED_EXERCISE),
                "values within the muscle category combine with OR");
        assertTrue(ids(allItems(owner, "/v1/catalogue/exercises?equipmentId=" + DUMBBELL + "&equipmentId=" + UUID.randomUUID() + "&limit=200")).contains(BUNDLED_EXERCISE),
                "an unknown valid filter ID does not prevent a match through another OR value");
        assertEquals(0, get(owner, "/v1/catalogue/exercises?muscleGroupId=" + UUID.randomUUID() + "&limit=200").json().path("items").size(),
                "unknown valid filter IDs match nothing");
    }

    @Test
    void exercisePagesUseAscendingCanonicalUuidAndContinueWithEquivalentFilterContext() {
        Account owner = account();
        List<String> expected = List.of(
                "00000000-0000-0000-0000-000000000001",
                "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "80000000-0000-0000-0000-000000000000",
                "ffffffff-ffff-ffff-ffff-ffffffffffff");
        for (String id : expected) customExercise(owner.id(), id, "qa-keyset-fixture " + id, "null");

        String base = "/v1/catalogue/exercises?q=qa-keyset-fixture&limit=1";
        List<String> actual = new ArrayList<>();
        String next = base;
        for (int index = 0; index < expected.size(); index++) {
            JsonNode page = get(owner, next).json();
            assertEquals("gymtracka-catalogue-v1", page.path("catalogueVersion").asText());
            List<String> ids = itemIds(page);
            assertEquals(1, ids.size());
            actual.add(ids.get(0));
            assertPageEnvelope(page, index < expected.size() - 1);
            if (index < expected.size() - 1) {
                String token = page.path("nextPageToken").asText();
                next = "/v1/catalogue/exercises?q=qa-keyset-fixture&limit=1&pageToken=" + encode(token);
            }
        }
        assertEquals(expected, actual, "UUID bytewise order must not use signed Java UUID ordering");

        JsonNode defaultLimit = get(owner, "/v1/catalogue/exercises?q=").json();
        assertEquals(100, defaultLimit.path("items").size());
        assertTrue(defaultLimit.path("hasMore").asBoolean(), "the catalogue exceeds the default page size");
        assertTrue(defaultLimit.path("nextPageToken").isTextual());
        JsonNode defaultContinuation = get(owner, "/v1/catalogue/exercises?limit=100&pageToken=" + encode(defaultLimit.path("nextPageToken").asText())).json();
        assertEquals(100, defaultContinuation.path("items").size(), "omitted limit and explicit default share the token context");
        assertTrue(defaultContinuation.path("hasMore").asBoolean());
        assertNotEquals(defaultLimit.path("items").get(0).path("id").asText(), defaultContinuation.path("items").get(0).path("id").asText());
        JsonNode maxPage = get(owner, "/v1/catalogue/exercises?limit=200").json();
        assertEquals(200, maxPage.path("items").size());
        assertPageEnvelope(maxPage, true);
        JsonNode empty = get(owner, "/v1/catalogue/exercises?limit=200&muscleGroupId=" + UUID.randomUUID()).json();
        assertTrue(empty.path("items").isEmpty());
        assertPageEnvelope(empty, false);
        JsonNode exactFirst = get(owner, "/v1/catalogue/exercises?q=qa-keyset-fixture&limit=2").json();
        assertEquals(2, exactFirst.path("items").size());
        assertPageEnvelope(exactFirst, true);
        JsonNode exactLast = get(owner, "/v1/catalogue/exercises?q=qa-keyset-fixture&limit=2&pageToken=" + encode(exactFirst.path("nextPageToken").asText())).json();
        assertEquals(2, exactLast.path("items").size());
        assertPageEnvelope(exactLast, false);
    }

    @Test
    void workoutPagesSortByStartedAtThenDescendingCanonicalUuidAndFilterExactStatus() {
        Account owner = account();
        Account foreign = account();
        Instant older = Instant.parse("2025-01-01T00:00:00.000Z");
        Instant tied = Instant.parse("2025-02-01T00:00:00.123Z");
        String[] tieIds = {"7fffffff-ffff-ffff-ffff-ffffffffffff", "80000000-0000-0000-0000-000000000000"};
        activeWorkout(owner.id(), "00000000-0000-0000-0000-000000000003", "active summary", older.plusSeconds(7200), 6);
        finishedWorkout(owner.id(), "00000000-0000-0000-0000-000000000001", "older", older, older.plusSeconds(3600), 2);
        finishedWorkout(owner.id(), tieIds[0], "tie low", tied, tied.plusSeconds(3600), 4);
        finishedWorkout(owner.id(), tieIds[1], "tie high", tied, tied.plusSeconds(3600), 5);
        finishedWorkout(foreign.id(), "ffffffff-ffff-ffff-ffff-ffffffffffff", "foreign", tied, tied.plusSeconds(1), 1);

        JsonNode first = get(owner, "/v1/workouts?status=finished&limit=1").json();
        assertEquals(List.of(tieIds[1]), itemIds(first));
        assertPageEnvelope(first, true);
        Reply changedStatus = get(owner, "/v1/workouts?status=active&limit=1&pageToken=" + encode(first.path("nextPageToken").asText()));
        assertEquals(400, changedStatus.status());
        assertEquals("invalid_page_token", changedStatus.json().path("errorCode").asText());
        JsonNode second = get(owner, "/v1/workouts?status=finished&limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(tieIds[0]), itemIds(second));
        assertPageEnvelope(second, true);
        JsonNode last = get(owner, "/v1/workouts?status=finished&limit=1&pageToken=" + encode(second.path("nextPageToken").asText())).json();
        assertEquals(List.of("00000000-0000-0000-0000-000000000001"), itemIds(last));
        assertPageEnvelope(last, false);
        assertFalse(itemIds(first).contains("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        JsonNode summaries = get(owner, "/v1/workouts?status=finished&limit=200").json();
        JsonNode summary = summaries.path("items").get(0);
        assertEquals("finished", summary.path("status").asText());
        assertEquals("tie high", summary.path("name").asText());
        assertEquals(5, summary.path("revision").asLong());
        assertEquals("2025-02-01T00:00:00.123Z", summary.path("startedAt").asText());
        assertTrue(summary.path("finishedAt").isTextual());
        assertFalse(summary.has("exercises"), "list items are summaries, not full workout aggregates");
        JsonNode activePage = get(owner, "/v1/workouts?status=active&limit=200").json();
        assertEquals(List.of("00000000-0000-0000-0000-000000000003"), itemIds(activePage));
        JsonNode activeSummary = activePage.path("items").get(0);
        assertEquals("active", activeSummary.path("status").asText());
        assertTrue(activeSummary.path("finishedAt").isNull());
        assertEquals(6, activeSummary.path("revision").asLong());
    }

    @Test
    void savedRoutinePagesExcludeDraftsAndReturnWholeCanonicalAggregates() {
        Account owner = account();
        String low = "00000000-0000-0000-0000-000000000002";
        String high = "80000000-0000-0000-0000-000000000002";
        savedRoutine(owner.id(), high, "Complete saved", 7);
        savedRoutine(owner.id(), low, "Empty saved", 3);
        UUID draft = UUID.randomUUID();
        jdbc.update("insert into routines(id,owner_id,name,is_draft,revision) values(?,?,?,true,1)", draft, owner.id(), "hidden draft");
        UUID exercise = customExercise(owner.id(), "Before browse rename");
        UUID entry = UUID.randomUUID(), set = UUID.randomUUID();
        jdbc.update("insert into routine_exercises(id,routine_id,exercise_id,position,rest_duration_seconds) values(?,?,?,0,75)", entry, UUID.fromString(high), exercise);
        jdbc.update("insert into routine_sets(id,routine_exercise_id,position,repetitions,weight_kg) values(?,?,0,8,42.5)", set, entry);

        JsonNode first = get(owner, "/v1/routines?limit=1").json();
        assertEquals(List.of(low), itemIds(first));
        assertPageEnvelope(first, true);
        JsonNode last = get(owner, "/v1/routines?limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(high), itemIds(last));
        assertPageEnvelope(last, false);
        JsonNode routine = last.path("items").get(0);
        assertFalse(routine.path("isDraft").asBoolean());
        assertEquals("Complete saved", routine.path("name").asText());
        assertEquals(7, routine.path("revision").asLong());
        assertEquals(1, routine.path("exercises").size());
        JsonNode routineExercise = routine.path("exercises").get(0);
        assertEquals(entry.toString(), routineExercise.path("id").asText());
        assertEquals(exercise.toString(), routineExercise.path("exerciseId").asText());
        assertEquals("Before browse rename", routineExercise.path("exerciseName").asText());
        assertEquals(75, routineExercise.path("restDurationSeconds").asInt());
        assertEquals(set.toString(), routineExercise.path("sets").get(0).path("id").asText());
        assertEquals(8, routineExercise.path("sets").get(0).path("repetitions").asInt());
        assertEquals(42.5, routineExercise.path("sets").get(0).path("weightKg").asDouble());
        JsonNode emptyRoutine = first.path("items").get(0);
        assertTrue(emptyRoutine.path("exercises").isEmpty(), "a valid empty saved autosave remains a list item");
        assertFalse(itemIds(last).contains(draft.toString()));

        Reply rename = mutate(owner, "PUT", "/v1/exercises/" + exercise,
                "{\"id\":\"" + exercise + "\",\"name\":\"After browse rename\"}");
        assertEquals(200, rename.status(), rename.text());
        JsonNode refreshed = get(owner, "/v1/routines?limit=200").json();
        JsonNode refreshedRoutine = item(refreshed, UUID.fromString(high));
        assertEquals("After browse rename", refreshedRoutine.path("exercises").get(0).path("exerciseName").asText());
        assertEquals(7, refreshedRoutine.path("revision").asLong(), "derived-name refresh does not bump the parent revision");
        assertEquals(List.of(exercise.toString()), itemIds(get(owner, "/v1/catalogue/exercises?q=After%20browse%20rename&limit=200").json()));
    }

    @Test
    void allListFamiliesExcludeForeignAndTombstonedPrivateRoots() {
        Account owner = account();
        Account other = account();
        UUID hiddenExercise = customExercise(owner.id(), "retired search record");
        UUID foreignExercise = customExercise(other.id(), "private foreign record");
        Instant start = Instant.parse("2025-03-01T00:00:00.123Z");
        Instant finish = Instant.parse("2025-03-01T01:00:00.123Z");
        String finishedId = "00000000-0000-0000-0000-000000000021";
        String foreignWorkout = "00000000-0000-0000-0000-000000000022";
        finishedWorkout(owner.id(), finishedId, "deleted history", start, finish, 4);
        finishedWorkout(other.id(), foreignWorkout, "foreign history", start, finish, 2);
        String routineId = "00000000-0000-0000-0000-000000000023";
        String foreignRoutine = "00000000-0000-0000-0000-000000000024";
        savedRoutine(owner.id(), routineId, "deleted routine", 3);
        savedRoutine(other.id(), foreignRoutine, "foreign routine", 1);
        jdbc.update("update exercises set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", hiddenExercise);
        jdbc.update("update workouts set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", UUID.fromString(finishedId));
        jdbc.update("update routines set deleted_at=clock_timestamp(),revision=revision+1,updated_at=clock_timestamp() where id=?", UUID.fromString(routineId));

        assertFalse(itemIds(get(owner, "/v1/catalogue/exercises?q=retired%20search%20record&limit=200").json()).contains(hiddenExercise.toString()));
        assertFalse(itemIds(get(owner, "/v1/catalogue/exercises?q=private%20foreign%20record&limit=200").json()).contains(foreignExercise.toString()));
        assertFalse(itemIds(get(owner, "/v1/workouts?status=finished&limit=200").json()).contains(finishedId));
        assertFalse(itemIds(get(owner, "/v1/workouts?status=finished&limit=200").json()).contains(foreignWorkout));
        assertFalse(itemIds(get(owner, "/v1/routines?limit=200").json()).contains(routineId));
        assertFalse(itemIds(get(owner, "/v1/routines?limit=200").json()).contains(foreignRoutine));
    }

    @Test
    void laterExercisePagesReflectCurrentMembershipAndRenamedSearchText() {
        Account owner = account();
        String before = "00000000-0000-0000-0000-000000000100";
        String removedByRename = "00000000-0000-0000-0000-000000000300";
        customExercise(owner.id(), before, "live member first", "null");
        customExercise(owner.id(), removedByRename, "live member last", "null");
        JsonNode first = get(owner, "/v1/catalogue/exercises?q=live%20member&limit=1").json();
        assertEquals(List.of(before), itemIds(first));
        assertPageEnvelope(first, true);

        String inserted = "00000000-0000-0000-0000-000000000200";
        Reply created = mutate(owner, "POST", "/v1/exercises", "{\"id\":\"" + inserted + "\",\"name\":\"live member middle\"}");
        assertEquals(201, created.status(), created.text());
        String renameBody = "{\"id\":\"" + removedByRename + "\",\"name\":\"renamed out of search\"}";
        Reply renamed = mutate(owner, "PUT", "/v1/exercises/" + removedByRename, renameBody);
        assertEquals(200, renamed.status(), renamed.text());

        JsonNode continuation = get(owner, "/v1/catalogue/exercises?q=live%20member&limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(inserted), itemIds(continuation), "a new matching member after the anchor is visible, while a renamed-out member is not frozen into the traversal");
        assertPageEnvelope(continuation, false);
    }

    @Test
    void deletedExerciseDisappearsFromTheUnfrozenContinuation() {
        Account owner = account();
        String firstId = "00000000-0000-0000-0000-000000000400";
        String deletedId = "00000000-0000-0000-0000-000000000500";
        String lastId = "00000000-0000-0000-0000-000000000600";
        customExercise(owner.id(), firstId, "delete-page fixture first", "null");
        customExercise(owner.id(), deletedId, "delete-page fixture middle", "null");
        customExercise(owner.id(), lastId, "delete-page fixture last", "null");
        JsonNode first = get(owner, "/v1/catalogue/exercises?q=delete-page%20fixture&limit=1").json();
        assertEquals(List.of(firstId), itemIds(first));
        Reply deleted = mutate(owner, "DELETE", "/v1/exercises/" + deletedId, null);
        assertEquals(204, deleted.status(), deleted.text());
        JsonNode continuation = get(owner, "/v1/catalogue/exercises?q=delete-page%20fixture&limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(lastId), itemIds(continuation));
        assertPageEnvelope(continuation, false);
    }

    @Test
    void finishingAnActiveWorkoutChangesLiveStatusFilteredTraversal() {
        Account owner = account();
        Instant older = Instant.parse("2025-03-01T00:00:00.123Z");
        String existingFinished = "00000000-0000-0000-0000-000000000700";
        String becomingFinished = "00000000-0000-0000-0000-000000000200";
        String olderFinished = "00000000-0000-0000-0000-000000000100";
        finishedWorkout(owner.id(), existingFinished, "already finished", older.plusSeconds(3600), older.plusSeconds(7200), 2);
        finishedWorkout(owner.id(), olderFinished, "older finished", older.minusSeconds(3600), older.minusSeconds(1800), 3);
        Reply started = mutate(owner, "POST", "/v1/workouts", "{\"id\":\"" + becomingFinished + "\",\"name\":\"will finish\",\"startedAt\":\"2025-03-01T00:00:00.123Z\"}");
        assertEquals(201, started.status(), started.text());
        UUID entry = UUID.randomUUID(), set = UUID.randomUUID();
        String activeBody = completedWorkoutInput(becomingFinished, entry, set);
        Reply active = mutate(owner, "PUT", "/v1/workouts/" + becomingFinished, activeBody);
        assertEquals(200, active.status(), active.text());

        JsonNode first = get(owner, "/v1/workouts?status=finished&limit=1").json();
        assertEquals(List.of(existingFinished), itemIds(first));
        assertPageEnvelope(first, true);
        Reply finished = mutate(owner, "POST", "/v1/workouts/" + becomingFinished + "/finish", "{\"finishedAt\":\"2025-03-01T01:00:00.456Z\"}");
        assertEquals(200, finished.status(), finished.text());
        JsonNode continuation = get(owner, "/v1/workouts?status=finished&limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(becomingFinished), itemIds(continuation), "a root changed to finished after the anchor joins the live later page");
        assertPageEnvelope(continuation, true);
        JsonNode last = get(owner, "/v1/workouts?status=finished&limit=1&pageToken=" + encode(continuation.path("nextPageToken").asText())).json();
        assertEquals(List.of(olderFinished), itemIds(last));
        assertPageEnvelope(last, false);
        assertEquals(0, get(owner, "/v1/workouts?status=active&limit=200").json().path("items").size());
    }

    @Test
    void savedRoutineListKeepsRootChildrenAndExerciseNameFromOneSnapshotDuringAcceptedReplacement() throws Exception {
        Account owner = account();
        UUID exercise = customExercise(owner.id(), "Stable routine exercise name");
        String routineId = "00000000-0000-0000-0000-000000000a01";
        UUID oldEntry = UUID.randomUUID(), oldSet = UUID.randomUUID();
        savedRoutine(owner.id(), routineId, "Before replacement", 4);
        jdbc.update("insert into routine_exercises(id,routine_id,exercise_id,position,rest_duration_seconds) values(?,?,?,0,31)",
                oldEntry, UUID.fromString(routineId), exercise);
        jdbc.update("insert into routine_sets(id,routine_exercise_id,position,repetitions,weight_kg) values(?,?,0,5,12.5)",
                oldSet, oldEntry);

        RoutineReadGate gate = new RoutineReadGate(UUID.fromString(routineId));
        ACTIVE_ROUTINE_READ_GATE.set(gate);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingRead = executor.submit(() -> get(owner, "/v1/routines?limit=1"));
            assertTrue(gate.rootQueryReturned.await(10, TimeUnit.SECONDS),
                    "real list HTTP read must return the selected routine root before its child/name queries");

            UUID newEntry = UUID.randomUUID(), newSet = UUID.randomUUID();
            String body = "{\"id\":\"" + routineId + "\",\"name\":\"After replacement\",\"isDraft\":false,\"exercises\":[{" +
                    "\"id\":\"" + newEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":47,\"sets\":[{" +
                    "\"id\":\"" + newSet + "\",\"position\":0,\"repetitions\":9,\"weightKg\":27.5}]}]}";
            Reply replacement = mutate(owner, "PUT", "/v1/routines/" + routineId, body);
            assertEquals(200, replacement.status(), replacement.text());
            gate.continueRead.countDown();

            Reply oldSnapshot = pendingRead.get(10, TimeUnit.SECONDS);
            assertEquals(200, oldSnapshot.status(), oldSnapshot.text());
            JsonNode oldRoutine = item(oldSnapshot.json(), UUID.fromString(routineId));
            assertEquals("Before replacement", oldRoutine.path("name").asText());
            assertEquals(4, oldRoutine.path("revision").asLong());
            assertEquals(1, oldRoutine.path("exercises").size());
            JsonNode oldExercise = oldRoutine.path("exercises").get(0);
            assertEquals(oldEntry.toString(), oldExercise.path("id").asText());
            assertEquals("Stable routine exercise name", oldExercise.path("exerciseName").asText());
            assertEquals(31, oldExercise.path("restDurationSeconds").asInt());
            assertEquals(1, oldExercise.path("sets").size());
            assertEquals(oldSet.toString(), oldExercise.path("sets").get(0).path("id").asText());
            assertEquals(5, oldExercise.path("sets").get(0).path("repetitions").asInt());
            assertEquals(12.5, oldExercise.path("sets").get(0).path("weightKg").asDouble());

            Reply freshReply = get(owner, "/v1/routines?limit=1");
            assertEquals(200, freshReply.status(), freshReply.text());
            JsonNode freshRoutine = item(freshReply.json(), UUID.fromString(routineId));
            assertEquals("After replacement", freshRoutine.path("name").asText());
            assertEquals(5, freshRoutine.path("revision").asLong());
            assertEquals(1, freshRoutine.path("exercises").size());
            JsonNode freshExercise = freshRoutine.path("exercises").get(0);
            assertEquals(newEntry.toString(), freshExercise.path("id").asText());
            assertEquals("Stable routine exercise name", freshExercise.path("exerciseName").asText());
            assertEquals(47, freshExercise.path("restDurationSeconds").asInt());
            assertEquals(1, freshExercise.path("sets").size());
            assertEquals(newSet.toString(), freshExercise.path("sets").get(0).path("id").asText());
            assertEquals(9, freshExercise.path("sets").get(0).path("repetitions").asInt());
            assertEquals(27.5, freshExercise.path("sets").get(0).path("weightKg").asDouble());
        } finally {
            gate.continueRead.countDown();
            ACTIVE_ROUTINE_READ_GATE.compareAndSet(gate, null);
            executor.shutdownNow();
        }
    }

    @Test
    void savingADraftAddsItToTheCurrentSavedRoutineTraversal() {
        Account owner = account();
        String firstId = "00000000-0000-0000-0000-000000000800";
        String draftId = "00000000-0000-0000-0000-000000000900";
        String lastId = "00000000-0000-0000-0000-000000001000";
        savedRoutine(owner.id(), firstId, "first saved", 1);
        savedRoutine(owner.id(), lastId, "last saved", 2);
        UUID draftEntry = UUID.randomUUID();
        Reply draftCreated = mutate(owner, "POST", "/v1/routines", "{\"id\":\"" + draftId + "\"}");
        assertEquals(201, draftCreated.status(), draftCreated.text());
        String draftBody = "{\"id\":\"" + draftId + "\",\"name\":\"draft name\",\"isDraft\":true,\"exercises\":[{" +
                "\"id\":\"" + draftEntry + "\",\"exerciseId\":\"" + BUNDLED_EXERCISE + "\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[]}] }";
        Reply draftUpdated = mutate(owner, "PUT", "/v1/routines/" + draftId, draftBody);
        assertEquals(200, draftUpdated.status(), draftUpdated.text());

        JsonNode first = get(owner, "/v1/routines?limit=1").json();
        assertEquals(List.of(firstId), itemIds(first));
        assertPageEnvelope(first, true);
        Reply saved = mutate(owner, "POST", "/v1/routines/" + draftId + "/save", "{\"name\":\"promoted\"}");
        assertEquals(200, saved.status(), saved.text());
        JsonNode continuation = get(owner, "/v1/routines?limit=1&pageToken=" + encode(first.path("nextPageToken").asText())).json();
        assertEquals(List.of(draftId), itemIds(continuation), "a newly saved draft is visible after the last key, not frozen out");
        assertFalse(continuation.path("items").get(0).path("isDraft").asBoolean());
        assertPageEnvelope(continuation, true);
    }

    @Test
    void listReadsDoNotChangeDomainSyncReplayOrCredentialState() {
        Account owner = account();
        customExercise(owner.id(), "effect check");
        activeWorkout(owner.id(), "00000000-0000-0000-0000-000000000013", "active", Instant.parse("2025-01-01T00:00:00.123Z"), 2);
        savedRoutine(owner.id(), "00000000-0000-0000-0000-000000000014", "saved", 3);
        String before = accountState(owner.id());

        String suppliedKey = UUID.randomUUID().toString();
        Reply keyedRead = getWithKey(owner, "/v1/catalogue/exercises?q=effect%20check", suppliedKey);
        assertEquals(200, keyedRead.status());
        Reply repeatedKeyedRead = getWithKey(owner, "/v1/catalogue/exercises?q=effect%20check", suppliedKey);
        assertEquals(200, repeatedKeyedRead.status());
        assertEquals(itemIds(keyedRead.json()), itemIds(repeatedKeyedRead.json()),
                "an optional idempotency key does not create a mutation or change list results");
        assertEquals(200, get(owner, "/v1/workouts?status=active").status());
        assertEquals(200, get(owner, "/v1/routines").status());
        assertEquals(200, get(owner, "/v1/routines").status(), "repeated reads are ordinary fresh reads, not idempotent replay");
        assertEquals(400, get(owner, "/v1/routines?unexpected=true").status());
        assertEquals(401, get(null, "/v1/catalogue/exercises").status());
        assertEquals(401, get(null, "/v1/workouts").status());
        assertEquals(401, get(null, "/v1/routines").status());
        assertEquals(before, accountState(owner.id()), "GETs do not write aggregates, cursors, replay evidence or credential expiries");
    }

    @Test
    void querySyntaxAndLimitsUseSafeInvalidRequestErrors() {
        Account owner = account();
        List<String> invalidPaths = List.of(
                "/v1/catalogue/exercises?limit=0",
                "/v1/catalogue/exercises?limit=201",
                "/v1/catalogue/exercises?limit=abc",
                "/v1/catalogue/exercises?limit=1&limit=2",
                "/v1/catalogue/exercises?unknown=value",
                "/v1/catalogue/exercises?muscleGroupId=" + SHOULDERS + "," + MIDDLE_BACK,
                "/v1/catalogue/exercises?equipmentId=not-a-uuid",
                "/v1/workouts?status=Finished",
                "/v1/workouts?status=active&status=finished",
                "/v1/routines?limit=1&unexpected=true");
        for (String path : invalidPaths) {
            Reply reply = get(owner, path);
            assertEquals(400, reply.status(), path + ": " + reply.text());
            assertEquals("invalid_request", reply.json().path("errorCode").asText(), path);
            assertTrue(reply.json().size() <= 2, "safe error shape for " + path);
        }
        assertEquals(200, get(owner, "/v1/catalogue/exercises?limit=1").status());
        assertEquals(200, get(owner, "/v1/catalogue/exercises?limit=200").status());
    }

    @Test
    void normalizedFiltersAndSameAccountSessionCanContinueWithoutSlidingContext() {
        Account owner = account();
        UUID firstId = customExercise(owner.id(), "CAFÉ plan first");
        UUID secondId = customExercise(owner.id(), "Café plan second");
        for (UUID exercise : List.of(firstId, secondId)) {
            addExerciseMuscle(exercise, SHOULDERS, "primary");
            addExerciseMuscle(exercise, MIDDLE_BACK, "secondary");
        }

        String initialPath = "/v1/catalogue/exercises?q=" + encode("CAFÉ") + "&muscleGroupId=" + SHOULDERS +
                "&muscleGroupId=" + MIDDLE_BACK + "&muscleGroupId=" + SHOULDERS + "&limit=1";
        JsonNode first = get(owner, initialPath).json();
        assertEquals(1, first.path("items").size());
        assertPageEnvelope(first, true);
        Account secondSession = session(owner.id());
        String continuation = "/v1/catalogue/exercises?q=" + encode("café") + "&muscleGroupId=" + MIDDLE_BACK +
                "&muscleGroupId=" + SHOULDERS + "&limit=1&pageToken=" + encode(first.path("nextPageToken").asText());
        JsonNode second = get(secondSession, continuation).json();
        assertEquals(1, second.path("items").size());
        assertNotEquals(first.path("items").get(0).path("id").asText(), second.path("items").get(0).path("id").asText());
        assertPageEnvelope(second, false);
    }

    @Test
    void listContinuationSurvivesARealJvmRestartWithTheStableConfiguredKey() throws Exception {
        Account owner = account();
        int childPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            childPort = socket.getLocalPort();
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process firstProcess = startBrowseProcess(java, classpath, childPort);
        String token;
        try {
            awaitBrowseProcess(childPort);
            Reply first = getAt(childPort, owner, "/v1/catalogue/exercises?limit=1");
            assertEquals(200, first.status(), first.text());
            assertPageEnvelope(first.json(), true);
            token = first.json().path("nextPageToken").asText();
        } finally {
            stopBrowseProcess(firstProcess);
        }

        Process restartedProcess = startBrowseProcess(java, classpath, childPort);
        try {
            awaitBrowseProcess(childPort);
            Reply continued = getAt(childPort, owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(token));
            assertEquals(200, continued.status(), continued.text());
            assertEquals(1, continued.json().path("items").size());
            assertPageEnvelope(continued.json(), true);
        } finally {
            stopBrowseProcess(restartedProcess);
        }
    }

    @Test
    void pageExpiryIsFixedAndExpiredContextIsCheckedBeforeExpiry() {
        Account owner = account();
        Instant started = Instant.parse("2031-02-03T04:05:06.000Z");
        when(serviceClock.instant()).thenReturn(started);
        JsonNode first = get(owner, "/v1/catalogue/exercises?limit=1").json();
        assertPageEnvelope(first, true);
        String token = first.path("nextPageToken").asText();
        String sameContext = "/v1/catalogue/exercises?limit=1&pageToken=" + encode(token);

        when(serviceClock.instant()).thenReturn(started.plusSeconds(899));
        Reply almostExpiredReply = get(owner, sameContext);
        assertEquals(200, almostExpiredReply.status());
        JsonNode almostExpired = almostExpiredReply.json();
        assertPageEnvelope(almostExpired, true);
        String preservedToken = almostExpired.path("nextPageToken").asText();
        when(serviceClock.instant()).thenReturn(started.plusSeconds(900));
        Reply wrongContextAtExpiry = get(owner, "/v1/catalogue/exercises?limit=2&pageToken=" + encode(token));
        assertEquals(400, wrongContextAtExpiry.status());
        assertEquals("invalid_page_token", wrongContextAtExpiry.json().path("errorCode").asText());
        Reply tamperedAtExpiry = get(owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(mutateToken(token)));
        assertEquals(400, tamperedAtExpiry.status());
        assertEquals("invalid_page_token", tamperedAtExpiry.json().path("errorCode").asText());
        Reply exactExpiry = get(owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(preservedToken));
        assertEquals(410, exactExpiry.status());
        assertEquals("page_expired", exactExpiry.json().path("errorCode").asText());
        JsonNode freshStart = get(owner, "/v1/catalogue/exercises?limit=1").json();
        assertEquals(1, freshStart.path("items").size(), "omitting an expired token starts a fresh live list");
        assertPageEnvelope(freshStart, true);
        String freshToken = freshStart.path("nextPageToken").asText();
        when(serviceClock.instant()).thenReturn(started.plusSeconds(1799));
        assertEquals(200, get(owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(freshToken)).status());
        when(serviceClock.instant()).thenReturn(started.plusSeconds(1800));
        Reply freshExactExpiry = get(owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(freshToken));
        assertEquals(410, freshExactExpiry.status());
        assertEquals("page_expired", freshExactExpiry.json().path("errorCode").asText());
    }

    @Test
    void validPageTokenDoesNotBypassLiveLogoutOrDisabledAccountAuthorization() {
        Account loggedOut = account();
        JsonNode first = get(loggedOut, "/v1/catalogue/exercises?limit=1").json();
        assertPageEnvelope(first, true);
        String continuation = "/v1/catalogue/exercises?limit=1&pageToken=" + encode(first.path("nextPageToken").asText());
        assertEquals(401, get(null, continuation).status(), "the token is not an authorization credential");
        Reply logout = logout(loggedOut.refreshToken());
        assertEquals(204, logout.status(), logout.text());
        Reply afterLogout = get(loggedOut, continuation);
        assertEquals(401, afterLogout.status());
        assertEquals("authentication_required", afterLogout.json().path("errorCode").asText());

        Account disabled = account();
        JsonNode disabledFirst = get(disabled, "/v1/catalogue/exercises?limit=1").json();
        assertPageEnvelope(disabledFirst, true);
        jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", disabled.id());
        Reply afterDisable = get(disabled, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(disabledFirst.path("nextPageToken").asText()));
        assertEquals(401, afterDisable.status());
        assertEquals("authentication_required", afterDisable.json().path("errorCode").asText());
    }

    @Test
    void invalidPageTokensNeverRestartOrCrossEndpointOrAccount() {
        Account owner = account();
        Account other = account();
        for (int i = 1; i <= 2; i++) customExercise(owner.id(), "token exercise " + i);
        JsonNode first = get(owner, "/v1/catalogue/exercises?limit=1").json();
        String token = first.path("nextPageToken").asText();
        assertFalse(token.isEmpty());
        String ownerState = accountState(owner.id());
        String otherState = accountState(other.id());

        Reply tampered = get(owner, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(mutateToken(token)));
        assertEquals(400, tampered.status());
        assertEquals("invalid_page_token", tampered.json().path("errorCode").asText());
        Reply wrongLimit = get(owner, "/v1/catalogue/exercises?limit=2&pageToken=" + encode(token));
        assertEquals("invalid_page_token", wrongLimit.json().path("errorCode").asText());
        Reply wrongEndpoint = get(owner, "/v1/routines?limit=1&pageToken=" + encode(token));
        assertEquals("invalid_page_token", wrongEndpoint.json().path("errorCode").asText());
        Reply wrongAccount = get(other, "/v1/catalogue/exercises?limit=1&pageToken=" + encode(token));
        assertEquals("invalid_page_token", wrongAccount.json().path("errorCode").asText());
        Reply wrongFilters = get(owner, "/v1/catalogue/exercises?q=changed&limit=1&pageToken=" + encode(token));
        assertEquals("invalid_page_token", wrongFilters.json().path("errorCode").asText());
        Reply emptyToken = get(owner, "/v1/catalogue/exercises?pageToken=");
        assertEquals("invalid_page_token", emptyToken.json().path("errorCode").asText());
        Reply malformedToken = get(owner, "/v1/catalogue/exercises?pageToken=not-a-valid-token");
        assertEquals("invalid_page_token", malformedToken.json().path("errorCode").asText());
        assertFalse(tampered.text().contains(owner.id().toString()));
        assertFalse(wrongAccount.text().contains(owner.id().toString()));
        assertEquals(ownerState, accountState(owner.id()), "invalid continuations have no state effects");
        assertEquals(otherState, accountState(other.id()), "foreign-account token attempts have no state effects");
    }

    private String mutateToken(String token) {
        int index = token.length() / 2;
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        return token.substring(0, index) + replacement + token.substring(index + 1);
    }
}
