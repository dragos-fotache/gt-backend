package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier.Verified;
import com.dragosfotache.gtbackend.sync.SyncHistoryPruningService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9a.2: complete independent export oracle.
 *
 * <p>Every expected domain value below is hand-authored in this file together with the
 * pinned catalogue names/IDs from {@code gymtracka-catalogue-v1}. Setup responses supply
 * only service-generated transport needs (bearer access); no expected domain field is
 * copied from a setup response, GET response, mapper, or observed export.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportOracleHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CATALOGUE_VERSION = "gymtracka-catalogue-v1";

    // Pinned catalogue references (names from the pinned seed, hand-recorded here).
    private static final String MUSCLE_CHEST = "6427e37e-b443-4577-8d16-635b7607dfeb";
    private static final String MUSCLE_BICEPS = "aef54d45-3433-420b-955e-e0d7950c06a1";
    private static final String EQUIPMENT_OTHER = "b84e44eb-3b18-4891-bc49-6b09cbecb87f";

    // Hand-authored intended domain content for account A (names/values fixed; IDs
    // generated per publish call because root identity is globally reserved).
    private static final String ALPHA_NAME = "Alpha Press";
    private static final String BETA_NAME = "Beta Curl";
    private static final String SAVED_NAME = "Second Routine";
    private static final String ACTIVE_NAME = "Morning Session";
    private static final String ACTIVE_STARTED = "2026-09-01T07:00:00Z";
    private static final String FINISHED_NAME = "Evening Session";
    private static final String FINISHED_STARTED = "2026-09-02T08:00:00Z";
    private static final String FINISHED_AT = "2026-09-02T09:00:00Z";

    private record PublishedIds(String alpha, String beta, String draft, String saved, String emptySaved,
                                String active, String finished) {}

    private static final Set<String> FORBIDDEN_NESTED_KEYS = Set.of(
            "revision", "mutationId", "cursor", "nextCursor", "ownerId", "sessionId",
            "fingerprint", "refreshToken", "accessToken", "credential", "accountId");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "export-oracle-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncHistoryPruningService pruning;
    @MockitoBean
    GoogleTokenVerifier verifier;

    private record BoundAccount(UUID accountId, String subject, String accessToken) {}
    private record Reply(int status, String body) {
        JsonNode node() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Response is not JSON: " + body, e);
            }
        }
    }

    private Reply post(String path, String body, String bearer, String key) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private Reply authed(String bearer, String method, String route, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .header("Idempotency-Key", UUID.randomUUID().toString());
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " failed: " + route, e);
        }
    }

    private BoundAccount provision(String subject, String token) {
        Reply challenge = post("/v1/auth/google/challenge", "{}", null, null);
        assertEquals(200, challenge.status, challenge.body);
        lenient().when(verifier.verify(token, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply grant = post("/v1/auth/google",
                "{\"challengeId\":\"" + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}",
                null, UUID.randomUUID().toString());
        assertEquals(200, grant.status, grant.body);
        return new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject, grant.node().path("accessToken").asText());
    }

    private Reply exportFor(BoundAccount account, String tokenName) {
        Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}", null, null);
        assertEquals(200, challenge.status, challenge.body);
        lenient().when(verifier.verify(tokenName, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(account.subject, null));
        return post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                        + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + tokenName + "\"}",
                null, null);
    }

    private PublishedIds publishRichData(BoundAccount account) {
        String alphaId = "0" + UUID.randomUUID().toString().substring(1);
        String betaId = "f" + UUID.randomUUID().toString().substring(1);
        String draftId = UUID.randomUUID().toString();
        String savedId = UUID.randomUUID().toString();
        String emptySavedId = UUID.randomUUID().toString();
        String activeId = UUID.randomUUID().toString();
        String finishedId = UUID.randomUUID().toString();
        // Custom exercises: one fully described, one bare (null/empty metadata).
        Reply alpha = authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + alphaId + "\",\"name\":\"" + ALPHA_NAME + "\","
                        + "\"primaryMuscleGroupId\":\"" + MUSCLE_CHEST + "\","
                        + "\"secondaryMuscleGroupIds\":[\"" + MUSCLE_BICEPS + "\"],"
                        + "\"equipmentId\":\"" + EQUIPMENT_OTHER + "\",\"equipmentNumItems\":2,"
                        + "\"category\":\"strength\",\"difficultyLevel\":null,\"forceType\":\"push\","
                        + "\"mechanics\":null,\"instructions\":[\"Brace\",\"Press\"]}");
        assertEquals(201, alpha.status, alpha.body);
        Reply beta = authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + betaId + "\",\"name\":\"" + BETA_NAME + "\","
                        + "\"primaryMuscleGroupId\":null,\"secondaryMuscleGroupIds\":[],\"equipmentId\":null,"
                        + "\"category\":null,\"difficultyLevel\":null,\"forceType\":null,"
                        + "\"mechanics\":null,\"instructions\":null}");
        assertEquals(201, beta.status, beta.body);

        // Blank draft routine with one null-repetition set (null vs zero check lives in the workout).
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + draftId + "\"}").status);
        String draftEntry = UUID.randomUUID().toString();
        String draftSet = UUID.randomUUID().toString();
        Reply draftPut = authed(account.accessToken, "PUT", "/v1/routines/" + draftId,
                "{\"id\":\"" + draftId + "\",\"name\":\"\",\"isDraft\":true,\"exercises\":["
                        + "{\"id\":\"" + draftEntry + "\",\"exerciseId\":\"" + alphaId + "\",\"position\":0,"
                        + "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + draftSet
                        + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}");
        assertEquals(200, draftPut.status, draftPut.body);

        // Saved routine referencing the bare custom exercise.
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + savedId + "\"}").status);
        String savedEntry = UUID.randomUUID().toString();
        String savedSet = UUID.randomUUID().toString();
        Reply savedPut = authed(account.accessToken, "PUT", "/v1/routines/" + savedId,
                "{\"id\":\"" + savedId + "\",\"name\":\"" + SAVED_NAME + "\",\"isDraft\":true,\"exercises\":["
                        + "{\"id\":\"" + savedEntry + "\",\"exerciseId\":\"" + betaId + "\",\"position\":0,"
                        + "\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + savedSet
                        + "\",\"position\":0,\"repetitions\":8,\"weightKg\":20.5}]}]}");
        assertEquals(200, savedPut.status, savedPut.body);
        assertEquals(200, authed(account.accessToken, "POST", "/v1/routines/" + savedId + "/save",
                "{\"name\":\"" + SAVED_NAME + "\"}").status);

        // Blank empty saved autosave: draft with content, save, then clear to the legitimate empty saved state.
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + emptySavedId + "\"}").status);
        String emptyEntry = UUID.randomUUID().toString();
        String emptySet = UUID.randomUUID().toString();
        assertEquals(200, authed(account.accessToken, "PUT", "/v1/routines/" + emptySavedId,
                "{\"id\":\"" + emptySavedId + "\",\"name\":\"Temporary\",\"isDraft\":true,\"exercises\":["
                        + "{\"id\":\"" + emptyEntry + "\",\"exerciseId\":\"" + betaId + "\",\"position\":0,"
                        + "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + emptySet
                        + "\",\"position\":0,\"repetitions\":5,\"weightKg\":10}]}]}").status);
        assertEquals(200, authed(account.accessToken, "POST", "/v1/routines/" + emptySavedId + "/save",
                "{\"name\":\"Temporary\"}").status);
        assertEquals(200, authed(account.accessToken, "PUT", "/v1/routines/" + emptySavedId,
                "{\"id\":\"" + emptySavedId + "\",\"name\":\"\",\"isDraft\":false,\"exercises\":[]}").status);

        // Finished workout first: starting another workout tombstones the prior active root,
        // so the finished one must complete before the active one is posted.
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + finishedId + "\",\"name\":\"" + FINISHED_NAME + "\",\"startedAt\":\"" + FINISHED_STARTED + "\"}").status);
        String finishedEntry = UUID.randomUUID().toString();
        String finishedSet = UUID.randomUUID().toString();
        Reply finishedPut = authed(account.accessToken, "PUT", "/v1/workouts/" + finishedId,
                "{\"id\":\"" + finishedId + "\",\"name\":\"" + FINISHED_NAME + "\",\"status\":\"active\","
                        + "\"startedAt\":\"" + FINISHED_STARTED + "\",\"exercises\":["
                        + "{\"id\":\"" + finishedEntry + "\",\"exerciseId\":\"" + betaId + "\",\"position\":0,"
                        + "\"notes\":\"Evening notes\",\"restDurationSeconds\":60,\"sets\":["
                        + "{\"id\":\"" + finishedSet + "\",\"position\":0,\"repetitions\":10,\"weightKg\":40,"
                        + "\"isCompleted\":true,\"restSeconds\":60}]}]}");
        assertEquals(200, finishedPut.status, finishedPut.body);
        assertEquals(200, authed(account.accessToken, "POST", "/v1/workouts/" + finishedId + "/finish",
                "{\"finishedAt\":\"" + FINISHED_AT + "\"}").status);


        // Active workout second: stays active because no later workout starts after it.
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + activeId + "\",\"name\":\"" + ACTIVE_NAME + "\",\"startedAt\":\"" + ACTIVE_STARTED + "\"}").status);
        String activeEntry = UUID.randomUUID().toString();
        String setDone = UUID.randomUUID().toString();
        String setOpen = UUID.randomUUID().toString();
        Reply activePut = authed(account.accessToken, "PUT", "/v1/workouts/" + activeId,
                "{\"id\":\"" + activeId + "\",\"name\":\"" + ACTIVE_NAME + "\",\"status\":\"active\","
                        + "\"startedAt\":\"" + ACTIVE_STARTED + "\",\"exercises\":["
                        + "{\"id\":\"" + activeEntry + "\",\"exerciseId\":\"" + alphaId + "\",\"position\":0,"
                        + "\"notes\":\"Controlled depth\",\"restDurationSeconds\":90,\"sets\":["
                        + "{\"id\":\"" + setDone + "\",\"position\":0,\"repetitions\":8,\"weightKg\":100.0,"
                        + "\"isCompleted\":true,\"restSeconds\":87},"
                        + "{\"id\":\"" + setOpen + "\",\"position\":1,\"repetitions\":null,\"weightKg\":0,"
                        + "\"isCompleted\":false,\"restSeconds\":null}]}]}");
        assertEquals(200, activePut.status, activePut.body);

        // Tombstoned routine: created then deleted, must be excluded from export.
        String tombstone = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + tombstone + "\"}").status);
        assertEquals(204, authed(account.accessToken, "DELETE", "/v1/routines/" + tombstone, null).status);
        return new PublishedIds(alphaId, betaId, draftId, savedId, emptySavedId, activeId, finishedId);
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode element : array) {
            if (id.equals(element.path("id").asText())) {
                return element;
            }
        }
        fail("Expected element " + id + " in " + array);
        return null;
    }

    private static void assertTextOrdered(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(element -> ids.add(element.path("id").asText()));
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        assertEquals(sorted, ids, "array is not in canonical UUID ascending order: " + ids);
    }

    private static void assertNoForbiddenKeys(JsonNode node) {
        assertNoForbiddenKeys(node, true);
    }

    private static void assertNoForbiddenKeys(JsonNode node, boolean isRoot) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (!(isRoot && entry.getKey().equals("accountId"))) {
                    assertFalse(FORBIDDEN_NESTED_KEYS.contains(entry.getKey()),
                            "forbidden internal field: " + entry.getKey());
                }
                assertNoForbiddenKeys(entry.getValue(), false);
            });
        } else if (node.isArray()) {
            node.forEach(child -> assertNoForbiddenKeys(child, false));
        }
    }

    private static String customNames(JsonNode root) {
        StringBuilder names = new StringBuilder("[");
        root.path("customExercises").forEach(custom -> names.append(custom.path("id").asText())
                .append("=").append(custom.path("name").asText()).append(","));
        return names.append("]").toString();
    }

    @Test
    void emptyAccountExportsPresentButEmptyArrays() {
        BoundAccount account = provision("empty-export-subject", "empty-export-signup");
        Reply reply = exportFor(account, "empty-export-token");
        assertEquals(200, reply.status, reply.body);
        JsonNode root = reply.node();
        assertEquals(1, root.path("exportVersion").asInt());
        assertEquals(account.accountId.toString(), root.path("accountId").asText());
        assertEquals(CATALOGUE_VERSION, root.path("catalogueVersion").asText());
        assertTrue(root.path("customExercises").isArray() && root.path("customExercises").isEmpty());
        assertTrue(root.path("routines").isArray() && root.path("routines").isEmpty());
        assertTrue(root.path("workouts").isArray() && root.path("workouts").isEmpty());
        assertFalse(Instant.parse(root.path("exportedAt").asText()).isAfter(Instant.now().plusSeconds(60)));
    }

    @Test
    void completeExportMatchesHandAuthoredValues() {
        BoundAccount account = provision("oracle-subject", "oracle-signup");
        PublishedIds ids = publishRichData(account);
        Reply reply = exportFor(account, "oracle-token");
        assertEquals(200, reply.status, reply.body);
        JsonNode root = reply.node();

        assertEquals(1, root.path("exportVersion").asInt());
        assertEquals(account.accountId.toString(), root.path("accountId").asText());
        assertEquals(CATALOGUE_VERSION, root.path("catalogueVersion").asText());
        assertNoForbiddenKeys(root);

        JsonNode customs = root.path("customExercises");
        assertEquals(2, customs.size(), reply.body);
        assertTextOrdered(customs);
        JsonNode alpha = byId(customs, ids.alpha());
        assertEquals(ALPHA_NAME, alpha.path("name").asText());
        assertTrue(alpha.path("isUserCreated").asBoolean());
        assertEquals("strength", alpha.path("category").asText());
        assertTrue(alpha.path("difficultyLevel").isNull());
        assertEquals("push", alpha.path("forceType").asText());
        assertEquals(List.of("Brace", "Press"),
                List.of(alpha.path("instructions").get(0).asText(), alpha.path("instructions").get(1).asText()));
        JsonNode alphaMuscles = alpha.path("muscleGroups");
        assertEquals(2, alphaMuscles.size());
        assertEquals(MUSCLE_CHEST, alphaMuscles.get(0).path("id").asText());
        assertEquals("Chest", alphaMuscles.get(0).path("name").asText());
        assertEquals("primary", alphaMuscles.get(0).path("type").asText());
        assertEquals(MUSCLE_BICEPS, alphaMuscles.get(1).path("id").asText());
        assertEquals("Biceps", alphaMuscles.get(1).path("name").asText());
        assertEquals(1, alpha.path("equipment").size());
        assertEquals(EQUIPMENT_OTHER, alpha.path("equipment").get(0).path("id").asText());
        assertEquals("Other", alpha.path("equipment").get(0).path("name").asText());
        assertEquals(2, alpha.path("equipment").get(0).path("numItems").asInt());
        JsonNode beta = byId(customs, ids.beta());
        assertEquals(BETA_NAME, beta.path("name").asText());
        assertTrue(beta.path("muscleGroups").isArray() && beta.path("muscleGroups").isEmpty());
        assertTrue(beta.path("equipment").isArray() && beta.path("equipment").isEmpty());
        assertTrue(beta.path("instructions").isNull());

        JsonNode routines = root.path("routines");
        assertEquals(3, routines.size(), reply.body);
        assertTextOrdered(routines);
        JsonNode draft = byId(routines, ids.draft());
        assertEquals("", draft.path("name").asText());
        assertTrue(draft.path("isDraft").asBoolean());
        assertEquals(1, draft.path("exercises").size());
        assertEquals(ids.alpha(), draft.path("exercises").get(0).path("exerciseId").asText());
        assertEquals(ALPHA_NAME, draft.path("exercises").get(0).path("exerciseName").asText());
        assertTrue(draft.path("exercises").get(0).path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, draft.path("exercises").get(0).path("sets").get(0).path("weightKg").asDouble());
        JsonNode saved = byId(routines, ids.saved());
        assertEquals(SAVED_NAME, saved.path("name").asText());
        assertFalse(saved.path("isDraft").asBoolean());
        assertEquals(BETA_NAME, saved.path("exercises").get(0).path("exerciseName").asText());
        assertEquals(30, saved.path("exercises").get(0).path("restDurationSeconds").asInt());
        JsonNode emptySaved = byId(routines, ids.emptySaved());
        assertEquals("", emptySaved.path("name").asText());
        assertFalse(emptySaved.path("isDraft").asBoolean());
        assertTrue(emptySaved.path("exercises").isEmpty());

        JsonNode workouts = root.path("workouts");
        assertEquals(2, workouts.size(), reply.body);
        assertTextOrdered(workouts);
        JsonNode active = byId(workouts, ids.active());
        assertEquals(ACTIVE_NAME, active.path("name").asText());
        assertEquals("active", active.path("status").asText());
        assertEquals(Instant.parse(ACTIVE_STARTED), Instant.parse(active.path("startedAt").asText()));
        assertTrue(active.path("finishedAt").isNull());
        JsonNode activeExercise = active.path("exercises").get(0);
        assertEquals(ids.alpha(), activeExercise.path("exerciseId").asText());
        assertEquals(ALPHA_NAME, activeExercise.path("exerciseName").asText());
        assertEquals("Controlled depth", activeExercise.path("notes").asText());
        assertEquals(90, activeExercise.path("restDurationSeconds").asInt());
        JsonNode doneSet = activeExercise.path("sets").get(0);
        assertEquals(8, doneSet.path("repetitions").asInt());
        assertEquals(100.0, doneSet.path("weightKg").asDouble());
        assertTrue(doneSet.path("isCompleted").asBoolean());
        assertEquals(87, doneSet.path("restSeconds").asInt());
        JsonNode openSet = activeExercise.path("sets").get(1);
        assertTrue(openSet.path("repetitions").isNull(), "null repetitions must survive, distinct from zero");
        assertEquals(0, openSet.path("weightKg").asDouble(), "zero weight must survive, distinct from null");
        assertFalse(openSet.path("isCompleted").asBoolean());
        assertTrue(openSet.path("restSeconds").isNull());
        JsonNode finished = byId(workouts, ids.finished());
        assertEquals("finished", finished.path("status").asText());
        assertEquals(Instant.parse(FINISHED_STARTED), Instant.parse(finished.path("startedAt").asText()));
        assertEquals(Instant.parse(FINISHED_AT), Instant.parse(finished.path("finishedAt").asText()));
        assertEquals("Evening notes", finished.path("exercises").get(0).path("notes").asText());
    }

    @Test
    void tombstonedCatalogueAndForeignRowsAreExcluded() {
        BoundAccount account = provision("exclusion-subject", "exclusion-signup");
        BoundAccount foreign = provision("foreign-subject", "foreign-signup");
        publishRichData(account);
        Reply foreignCustom = authed(foreign.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Foreign private\"}");
        assertEquals(201, foreignCustom.status, foreignCustom.body);

        JsonNode root = exportFor(account, "exclusion-token").node();
        assertEquals(2, root.path("customExercises").size(), "customs: " + customNames(root));
        assertEquals(3, root.path("routines").size());
        assertEquals(2, root.path("workouts").size());
        for (JsonNode custom : root.path("customExercises")) {
            assertTrue(custom.path("isUserCreated").asBoolean());
            assertNotEquals("Foreign private", custom.path("name").asText());
        }
        JsonNode foreignRoot = exportFor(foreign, "foreign-token").node();
        assertEquals(1, foreignRoot.path("customExercises").size());
        assertEquals("Foreign private", foreignRoot.path("customExercises").get(0).path("name").asText());
    }

    @Test
    void oldRootsSurviveProductionHistoryPruning() {
        BoundAccount account = provision("pruning-subject", "pruning-signup");
        PublishedIds ids = publishRichData(account);
        jdbc.update("UPDATE sync_change_batches SET committed_at=clock_timestamp()-interval '91 days' WHERE account_id=?", account.accountId);
        int staleBefore = jdbc.queryForObject(
                "SELECT count(*) FROM sync_change_batches WHERE account_id=? AND committed_at<=clock_timestamp()-interval '90 days'",
                Integer.class, account.accountId);
        assertTrue(staleBefore > 0, "expected backdated batches to prune");
        pruning.pruneExpiredHistory();
        int staleAfter = jdbc.queryForObject(
                "SELECT count(*) FROM sync_change_batches WHERE account_id=? AND committed_at<=clock_timestamp()-interval '90 days'",
                Integer.class, account.accountId);
        assertEquals(0, staleAfter, "pruning must have removed the aged prefix");

        JsonNode root = exportFor(account, "pruning-token").node();
        assertEquals(2, root.path("customExercises").size(), "live roots must survive log pruning; customs: "
                + customNames(root));
        assertEquals(3, root.path("routines").size());
        assertEquals(2, root.path("workouts").size());
        assertEquals(ALPHA_NAME, byId(root.path("customExercises"), ids.alpha()).path("name").asText());
    }
}
