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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncPullRetentionHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "pull-retention-audience");
        registry.add("auth.encryption-key", () -> KEY);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;
    @Autowired PlatformTransactionManager txManager;

    private record Account(UUID id, String token) {}
    private record Reply(int status, String body) {
        JsonNode json() {
            try { return JSON.readTree(body); }
            catch (Exception e) { throw new AssertionError(body, e); }
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

    private Reply get(Account account, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + account.token()).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private Reply mutate(Account account, String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + account.token());
            if (body != null) builder.header("Content-Type", "application/json");
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private Reply pull(Account account, String cursor, Integer limit) {
        List<String> query = new ArrayList<>();
        if (cursor != null) query.add("cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        if (limit != null) query.add("limit=" + limit);
        return get(account, "/v1/sync/pull" + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    private List<String> publish(Account account, int count, String prefix) {
        List<String> mutations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String root = UUID.randomUUID().toString();
            mutations.add("{\"mutationId\":\"" + UUID.randomUUID() + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + root + "\",\"name\":\"" + prefix + i + "\"}}");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sync/push"))
                    .header("Authorization", "Bearer " + account.token()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mutations\":[" + String.join(",", mutations) + "]}")).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode results = JSON.readTree(response.body()).path("results");
            assertEquals(count, results.size());
            for (JsonNode outcome : results) assertEquals("accepted", outcome.path("status").asText());
            return java.util.stream.IntStream.range(0, results.size())
                    .mapToObj(i -> results.get(i).path("batch").path("cursor").asText()).toList();
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Test-only fixture: remove a complete prefix and advance its durable watermark atomically. */
    private JsonNode changeAt(JsonNode batches, String aggregateId, String operation, long revision) {
        for (JsonNode batch : batches) for (JsonNode change : batch.path("changes"))
            if (change.path("aggregateType").asText().equals("routine")
                    && change.path("aggregateId").asText().equals(aggregateId)
                    && change.path("operation").asText().equals(operation)
                    && change.path("revision").asLong() == revision) return change;
        throw new AssertionError("Missing routine " + aggregateId + " " + operation + " revision " + revision + " from " + batches);
    }

    private void pruneFixture(Account account, long through) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long current = jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=? for update", Long.class, account.id());
            long prior = jdbc.queryForObject("select pruned_through from account_sync_state where account_id=?", Long.class, account.id());
            assertTrue(through >= prior && through <= current);
            jdbc.update("delete from sync_changes where account_id=? and cursor<=?", account.id(), through);
            jdbc.update("delete from sync_change_batches where account_id=? and cursor<=?", account.id(), through);
            jdbc.update("update account_sync_state set pruned_through=? where account_id=?", through, account.id());
        });
    }

    @Test
    void pullPreservesSavedRoutineOrderedChildrenAndHistoricalPayloadAfterReplacementAndDelete() {
        Account owner = account();
        String exercise = UUID.randomUUID().toString();
        Reply exerciseCreated = mutate(owner, "POST", "/v1/exercises",
                "{\"id\":\"" + exercise + "\",\"name\":\"Routine exercise history\"}");
        assertEquals(201, exerciseCreated.status(), exerciseCreated.body());
        Reply exerciseBoundary = pull(owner, null, 1);
        assertEquals(1, exerciseBoundary.json().path("batches").size());
        assertEquals(exercise, exerciseBoundary.json().path("batches").get(0).path("changes").get(0).path("aggregateId").asText());
        String cursorAfterExercise = exerciseBoundary.json().path("nextCursor").asText();

        String routine = UUID.randomUUID().toString();
        String firstEntry = UUID.randomUUID().toString(), secondEntry = UUID.randomUUID().toString();
        String firstSet = UUID.randomUUID().toString(), secondSet = UUID.randomUUID().toString(), thirdSet = UUID.randomUUID().toString();
        Reply created = mutate(owner, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}");
        assertEquals(201, created.status(), created.body());
        String populated = "{\"id\":\"" + routine + "\",\"name\":\"Draft before save\",\"isDraft\":true,\"exercises\":[" +
                "{\"id\":\"" + firstEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":45,\"sets\":[" +
                "{\"id\":\"" + firstSet + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}," +
                "{\"id\":\"" + secondSet + "\",\"position\":1,\"repetitions\":8,\"weightKg\":17.5}]}," +
                "{\"id\":\"" + secondEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":1,\"restDurationSeconds\":0,\"sets\":[" +
                "{\"id\":\"" + thirdSet + "\",\"position\":0,\"repetitions\":0,\"weightKg\":null}]}]}";
        Reply draft = mutate(owner, "PUT", "/v1/routines/" + routine, populated);
        assertEquals(200, draft.status(), draft.body());
        Reply saved = mutate(owner, "POST", "/v1/routines/" + routine + "/save", "{\"name\":\"Saved routine history\"}");
        assertEquals(200, saved.status(), saved.body());

        String laterEntry = UUID.randomUUID().toString(), laterSet = UUID.randomUUID().toString();
        String later = "{\"id\":\"" + routine + "\",\"name\":\"Later routine state\",\"isDraft\":false,\"exercises\":[" +
                "{\"id\":\"" + laterEntry + "\",\"exerciseId\":\"" + exercise + "\",\"position\":0,\"restDurationSeconds\":7,\"sets\":[" +
                "{\"id\":\"" + laterSet + "\",\"position\":0,\"repetitions\":3,\"weightKg\":0}]}]}";
        assertEquals(200, mutate(owner, "PUT", "/v1/routines/" + routine, later).status());
        assertEquals(204, mutate(owner, "DELETE", "/v1/routines/" + routine, null).status());

        Reply history = pull(owner, cursorAfterExercise, 100);
        assertEquals(200, history.status(), history.body());
        assertEquals(5, history.json().path("batches").size());
        assertFalse(history.json().path("hasMore").asBoolean());
        JsonNode savedRecord = changeAt(history.json().path("batches"), routine, "upsert", 3).path("record");
        assertEquals("Saved routine history", savedRecord.path("name").asText());
        assertFalse(savedRecord.path("isDraft").asBoolean());
        assertEquals(2, savedRecord.path("exercises").size());
        JsonNode first = savedRecord.path("exercises").get(0), second = savedRecord.path("exercises").get(1);
        assertEquals(firstEntry, first.path("id").asText());
        assertEquals(0, first.path("position").asInt());
        assertEquals("Routine exercise history", first.path("exerciseName").asText());
        assertEquals(45, first.path("restDurationSeconds").asInt());
        assertEquals(2, first.path("sets").size());
        assertEquals(firstSet, first.path("sets").get(0).path("id").asText());
        assertTrue(first.path("sets").get(0).path("repetitions").isNull());
        assertEquals(0, first.path("sets").get(0).path("weightKg").asDouble());
        assertEquals(secondSet, first.path("sets").get(1).path("id").asText());
        assertEquals(1, first.path("sets").get(1).path("position").asInt());
        assertEquals(8, first.path("sets").get(1).path("repetitions").asInt());
        assertEquals(17.5, first.path("sets").get(1).path("weightKg").asDouble());
        assertEquals(secondEntry, second.path("id").asText());
        assertEquals(1, second.path("position").asInt());
        assertEquals(0, second.path("restDurationSeconds").asInt());
        assertEquals(thirdSet, second.path("sets").get(0).path("id").asText());
        assertEquals(0, second.path("sets").get(0).path("repetitions").asInt());
        assertTrue(second.path("sets").get(0).path("weightKg").isNull());
        assertEquals("Later routine state", changeAt(history.json().path("batches"), routine, "upsert", 4).path("record").path("name").asText());
        JsonNode deletion = changeAt(history.json().path("batches"), routine, "delete", 5);
        assertTrue(deletion.path("record").isMissingNode() || deletion.path("record").isNull());
    }

    @Test
    void controlledPrunedPrefixDistinguishesBeforeEqualAfterNullAllPrunedAgeAndForeignTokens() {
        Account account = account();
        Account foreign = account();
        String zero = pull(account, null, null).json().path("nextCursor").asText();
        List<String> cursors = publish(account, 3, "retained prefix ");
        List<String> foreignCursors = publish(foreign, 3, "foreign prefix ");
        String one = pull(account, null, 1).json().path("nextCursor").asText();
        String two = pull(account, one, 1).json().path("nextCursor").asText();
        String three = pull(account, two, 1).json().path("nextCursor").asText();
        assertEquals(cursors.get(0), one);
        assertEquals(cursors.get(1), two);
        assertEquals(cursors.get(2), three);
        assertEquals(foreignCursors.get(0), pull(foreign, null, 1).json().path("nextCursor").asText());

        jdbc.update("update sync_cursor_tokens set created_at=clock_timestamp()-interval '365 days' where account_id=? and cursor=0", account.id());
        jdbc.update("update sync_cursor_tokens set created_at=clock_timestamp()-interval '365 days' where account_id=? and cursor=1", foreign.id());
        assertEquals("invalid_cursor", pull(account, "unknown-token", null).json().path("errorCode").asText());
        Reply agedButUnpruned = pull(foreign, foreignCursors.get(0), 2);
        assertEquals(200, agedButUnpruned.status(), agedButUnpruned.body());
        assertEquals(2, agedButUnpruned.json().path("batches").size());

        pruneFixture(account, 1);
        Reply before = pull(account, zero, null);
        assertEquals(410, before.status(), before.body());
        assertEquals("cursor_expired", before.json().path("errorCode").asText());
        assertTrue(before.json().path("fullResyncRequired").asBoolean());
        Reply at = pull(account, one, 1);
        assertEquals(200, at.status(), at.body());
        assertEquals(1, at.json().path("batches").size());
        assertEquals(two, at.json().path("nextCursor").asText());
        Reply after = pull(account, two, 1);
        assertEquals(200, after.status(), after.body());
        assertEquals(1, after.json().path("batches").size());
        assertEquals(three, after.json().path("nextCursor").asText());
        Reply nullStartsRetained = pull(account, null, 1);
        assertEquals(200, nullStartsRetained.status(), nullStartsRetained.body());
        assertEquals(two, nullStartsRetained.json().path("nextCursor").asText(), "absent cursor begins after the removed prefix");

        Reply foreignInPrunedAccount = pull(account, foreignCursors.get(0), null);
        assertEquals(400, foreignInPrunedAccount.status());
        assertEquals("invalid_cursor", foreignInPrunedAccount.json().path("errorCode").asText(), "a foreign token is invalid even when this account has pruned history");
        for (String malformed : List.of("", "malformed-cursor")) {
            Reply invalid = pull(account, malformed, null);
            assertEquals(400, invalid.status());
            assertEquals("invalid_cursor", invalid.json().path("errorCode").asText());
        }

        pruneFixture(account, 3);
        Reply allGone = pull(account, null, 100);
        assertEquals(200, allGone.status(), allGone.body());
        assertTrue(allGone.json().path("batches").isEmpty());
        assertFalse(allGone.json().path("hasMore").asBoolean());
        assertTrue(allGone.json().path("nextCursor").asText().length() > 1);
        assertEquals(three, allGone.json().path("nextCursor").asText(), "retained-start on an empty tail is the pruned-through boundary");
        assertEquals(200, pull(account, three, 1).status(), "equality with the watermark remains usable");
        Reply nowExpired = pull(account, two, 1);
        assertEquals(410, nowExpired.status());
        assertTrue(nowExpired.json().path("fullResyncRequired").asBoolean());
    }
}
