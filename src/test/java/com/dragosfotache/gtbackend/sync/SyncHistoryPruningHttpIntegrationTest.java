package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/** Production cleanup integration tests: history is published over HTTP, then pruned by the real service. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncHistoryPruningHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "sync-pruning-audience";
    private static final long PRUNE_LOCK = 831_928_174L;
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("sync.history.cleanup.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;
    @Autowired SyncHistoryPruningService cleanup;
    @MockitoSpyBean Clock clock;

    private record Account(UUID id, String bearer) { }
    private record Reply(int status, String body) {
        JsonNode json() {
            try { return JSON.readTree(body); }
            catch (Exception e) { throw new AssertionError("Not JSON response: " + body, e); }
        }
    }

    @AfterEach
    void restoreServiceClock() {
        reset(clock);
    }

    private void setServiceTime(Instant instant) {
        doReturn(instant).when(clock).instant();
    }

    private Account account() {
        UUID id = UUID.randomUUID(), sessionId = UUID.randomUUID();
        String bearer = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", id);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", sessionId, id);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) "
                + "values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '2 hours')",
                crypto.digest(bearer), sessionId);
        return new Account(id, bearer);
    }

    private Reply request(Account account, String method, String path, String body, String key) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + account.bearer());
            if (body != null) request.header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) { throw new AssertionError("HTTP request failed: " + method + " " + path, e); }
    }

    private Reply pull(Account account, String cursor, Integer limit) {
        List<String> query = new ArrayList<>();
        if (cursor != null) query.add("cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        if (limit != null) query.add("limit=" + limit);
        return request(account, "GET", "/v1/sync/pull" + (query.isEmpty() ? "" : "?" + String.join("&", query)), null, null);
    }

    private String startWorkout(Account account, String name) {
        String id = id();
        Reply response = request(account, "POST", "/v1/workouts",
                "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"startedAt\":\"2025-01-01T00:00:00Z\"}", id());
        assertEquals(201, response.status(), response.body());
        return id;
    }

    private String createExercise(Account account, String name) {
        String id = id();
        Reply response = request(account, "POST", "/v1/exercises",
                "{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}", id());
        assertEquals(201, response.status(), response.body());
        return id;
    }

    private String pushCustom(Account account, UUID mutationId, String root, String name) {
        String mutation = "{\"mutationId\":\"" + mutationId + "\",\"type\":\"customExercise.upsert\","
                + "\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,"
                + "\"payload\":{\"id\":\"" + root + "\",\"name\":\"" + name + "\"}}";
        Reply response = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" + mutation + "]}", null);
        assertEquals(200, response.status(), response.body());
        return response.body();
    }

    private String nextCursor(Account account) {
        Reply response = pull(account, null, 100);
        assertEquals(200, response.status(), response.body());
        return response.json().path("nextCursor").asText();
    }

    private long cursorFor(String token, Account account) {
        Long cursor = jdbc.queryForObject("select cursor from sync_cursor_tokens where account_id=? and token=?",
                Long.class, account.id(), token);
        assertNotNull(cursor, "service-issued opaque cursor must be registered");
        return cursor;
    }

    private void setCommitTime(Account account, long cursor, Instant committedAt) {
        assertEquals(1, jdbc.update("update sync_change_batches set committed_at=? where account_id=? and cursor=?",
                Timestamp.from(committedAt), account.id(), cursor));
    }

    private int batchCount(Account account) {
        return jdbc.queryForObject("select count(*) from sync_change_batches where account_id=?", Integer.class, account.id());
    }

    private int changeCount(Account account) {
        return jdbc.queryForObject("select count(*) from sync_changes where account_id=?", Integer.class, account.id());
    }

    private long watermark(Account account) {
        return jdbc.queryForObject("select pruned_through from account_sync_state where account_id=?", Long.class, account.id());
    }

    private long committed(Account account) {
        return jdbc.queryForObject("select committed_cursor from account_sync_state where account_id=?", Long.class, account.id());
    }

    private String id() { return UUID.randomUUID().toString(); }

    @Test
    void prunesOnlyExactContiguousEligiblePrefixAndKeepsWholeBatchesAndOtherAccounts() {
        Instant serviceNow = Instant.parse("2030-01-01T00:00:00Z");
        Instant cutoff = serviceNow.minus(90, ChronoUnit.DAYS);
        setServiceTime(serviceNow);

        Account owner = account();
        Account other = account();
        String before = nextCursor(owner); // cursor 0, authentic service-issued token before any commit
        String oldActive = startWorkout(owner, "old active");                 // cursor 1: one change, before cutoff
        String newActive = startWorkout(owner, "new active");                 // cursor 2: DELETE + UPSERT, exactly cutoff
        createExercise(owner, "newer ineligible");                            // cursor 3: just after cutoff
        createExercise(owner, "clock-corrected older");                       // cursor 4: older timestamp, but behind cursor 3
        createExercise(other, "other account retained");                      // independent account history

        setCommitTime(owner, 1, cutoff.minus(1, ChronoUnit.MICROS));
        setCommitTime(owner, 2, cutoff);
        setCommitTime(owner, 3, cutoff.plus(1, ChronoUnit.MICROS));
        setCommitTime(owner, 4, cutoff.minus(1, ChronoUnit.DAYS));
        setCommitTime(other, 1, cutoff.plus(1, ChronoUnit.DAYS));
        jdbc.update("update sync_changes set expires_at=clock_timestamp()-interval '400 days' where account_id=?", owner.id());

        String foreignCursor = nextCursor(other);
        String firstCursor = pull(owner, null, 1).json().path("nextCursor").asText();
        String watermarkCursor = pull(owner, firstCursor, 1).json().path("nextCursor").asText();
        assertEquals(0, watermark(owner));
        assertEquals(4, committed(owner));
        assertEquals(4, batchCount(owner));
        assertEquals(1, batchCount(other));

        SyncHistoryPruningService.CleanupProgress result = cleanup.pruneExpiredHistory();
        assertTrue(result.accountsPruned() >= 1);
        assertEquals(2, watermark(owner), "strict-after eligibility includes equality and removes only the contiguous prefix");
        assertEquals(4, committed(owner), "pruning never allocates or advances committed_cursor");
        assertEquals(2, batchCount(owner));
        assertEquals(2, changeCount(owner), "the active-replacement multi-change batch is deleted whole");
        assertEquals(1, batchCount(other));
        assertEquals(1, changeCount(other));

        Reply stale = pull(owner, before, 100);
        assertEquals(410, stale.status(), stale.body());
        assertEquals("cursor_expired", stale.json().path("errorCode").asText());
        assertTrue(stale.json().path("fullResyncRequired").asBoolean());
        for (String invalid : List.of(foreignCursor, "unknown-prune-token", "")) {
            Reply rejectedCursor = pull(owner, invalid, 100);
            assertEquals(400, rejectedCursor.status(), rejectedCursor.body());
            assertEquals("invalid_cursor", rejectedCursor.json().path("errorCode").asText());
        }
        Reply atWatermark = pull(owner, watermarkCursor, 1);
        assertEquals(200, atWatermark.status(), atWatermark.body());
        assertEquals(1, atWatermark.json().path("batches").size());
        assertEquals(3, cursorFor(atWatermark.json().path("nextCursor").asText(), owner));
        Reply absent = pull(owner, null, 1);
        assertEquals(200, absent.status(), absent.body());
        assertEquals(1, absent.json().path("batches").size());
        assertEquals(3, cursorFor(absent.json().path("nextCursor").asText(), owner));
        assertEquals(0, jdbc.queryForObject("select count(*) from workouts where owner_id=? and id=? and status='active' and deleted_at is null",
                Integer.class, owner.id(), UUID.fromString(oldActive)));
        assertEquals(newActive, jdbc.queryForObject("select id::text from workouts where owner_id=? and status='active' and deleted_at is null", String.class, owner.id()));
    }

    @Test
    void prunesEntirelyEligibleAndAlreadyPrunedHistoryAndLeavesEmptyTailWithStableCursor() {
        Instant serviceNow = Instant.parse("2031-03-01T12:30:00Z");
        Instant cutoff = serviceNow.minus(90, ChronoUnit.DAYS);
        setServiceTime(serviceNow);
        Account account = account();
        String oldBoundary = nextCursor(account);
        createExercise(account, "eligible A");
        createExercise(account, "eligible B");
        setCommitTime(account, 1, cutoff.minus(20, ChronoUnit.DAYS));
        setCommitTime(account, 2, cutoff);

        cleanup.pruneExpiredHistory();
        assertEquals(2, watermark(account));
        assertEquals(0, batchCount(account));
        assertEquals(0, changeCount(account));
        Reply empty = pull(account, null, 100);
        assertEquals(200, empty.status(), empty.body());
        assertTrue(empty.json().path("batches").isEmpty());
        assertFalse(empty.json().path("hasMore").asBoolean());
        assertEquals(2, cursorFor(empty.json().path("nextCursor").asText(), account));
        assertEquals(200, pull(account, empty.json().path("nextCursor").asText(), 100).status());
        assertEquals(410, pull(account, oldBoundary, 100).status());

        cleanup.pruneExpiredHistory();
        assertEquals(2, watermark(account), "already-pruned accounts do not apply stale work twice");

        Account emptyAccount = account();
        cleanup.pruneExpiredHistory();
        assertEquals(0, watermark(emptyAccount));
        assertEquals(0, committed(emptyAccount));
        assertEquals(0, batchCount(emptyAccount));
    }

    @Test
    void failureAfterDeletesRollsBackWholeAccountAndRetrySucceeds() {
        setServiceTime(Instant.parse("2032-01-01T00:00:00Z"));
        Account account = account();
        createExercise(account, "atomic prune one");
        createExercise(account, "atomic prune two");
        for (long cursor = 1; cursor <= 2; cursor++)
            setCommitTime(account, cursor, Instant.parse("2031-01-01T00:00:00Z"));
        long originalCommitted = committed(account);
        createPruneFault("task8b_midpoint_fault", account.id());
        try {
            assertThrows(RuntimeException.class, cleanup::pruneExpiredHistory,
                    "the real service must surface the failed per-account transaction");
            assertEquals(0, watermark(account));
            assertEquals(originalCommitted, committed(account));
            assertEquals(2, batchCount(account));
            assertEquals(2, changeCount(account));
        } finally {
            dropPruneFault("task8b_midpoint_fault");
        }
        SyncHistoryPruningService.CleanupProgress retry = cleanup.pruneExpiredHistory();
        assertTrue(retry.batchesPruned() >= 2);
        assertEquals(2, watermark(account));
        assertEquals(0, batchCount(account));
        assertEquals(0, changeCount(account));
    }

    private void createPruneFault(String trigger, UUID accountId) {
        jdbc.execute("create function " + trigger + "() returns trigger language plpgsql as $$ begin "
                + "if NEW.account_id='" + accountId + "'::uuid then "
                + "raise exception 'test-only-prune-fault' using errcode='XX000'; end if; return NEW; end $$");
        jdbc.execute("create trigger " + trigger + " before update of pruned_through on account_sync_state "
                + "for each row execute function " + trigger + "()");
    }

    private void dropPruneFault(String trigger) {
        jdbc.execute("drop trigger " + trigger + " on account_sync_state");
        jdbc.execute("drop function " + trigger + "()");
    }

    @Test
    void cleanupAndHttpPullPublisherAndSecondCleanupSeeOnlyCommittedBoundaries() throws Exception {
        setServiceTime(Instant.parse("2033-01-01T00:00:00Z"));
        Account account = account();
        String initialBoundary = nextCursor(account); // durable cursor 0, before the HTTP publication
        createExercise(account, "history before pruning");
        setCommitTime(account, 1, Instant.parse("1900-01-01T00:00:00Z"));
        createAdvisoryGate("task8b_prune_advisory_gate", account.id());

        try (java.sql.Connection heldLock = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            heldLock.setAutoCommit(true);
            try (var lock = heldLock.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, PRUNE_LOCK);
                lock.execute();
            }

            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            try {
                var pruning = pool.submit(() -> {
                    cleanupStarted.countDown();
                    return cleanup.pruneExpiredHistory();
                });
                assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
                awaitSqlLock("update account_sync_state", 10);
                assertEquals(0, watermark(account), "deletes before the watermark remain uncommitted while gate is held");

                Reply concurrentPull = pull(account, initialBoundary, 100);
                assertEquals(200, concurrentPull.status(), concurrentPull.body());
                assertEquals(1, concurrentPull.json().path("batches").size(), "RR reader sees the complete pre-prune committed history");

                var competingCleanup = pool.submit(cleanup::pruneExpiredHistory);
                SyncHistoryPruningService.CleanupProgress skipped = competingCleanup.get(5, TimeUnit.SECONDS);
                assertTrue(skipped.accountsSkipped() >= 1, "a locked account is skipped rather than blocking another cleanup instance");
                assertEquals(0, watermark(account));

                var publisher = pool.submit(() -> createExercise(account, "publisher after pruning"));
                awaitSqlLock("for update", 10);
                try (var unlock = heldLock.prepareStatement("select pg_advisory_unlock(?)")) {
                    unlock.setLong(1, PRUNE_LOCK);
                    unlock.execute();
                }
                SyncHistoryPruningService.CleanupProgress pruned = pruning.get(10, TimeUnit.SECONDS);
                assertTrue(pruned.batchesPruned() >= 1);
                String published = publisher.get(10, TimeUnit.SECONDS);
                assertNotNull(published);

                assertEquals(1, watermark(account));
                assertEquals(2, committed(account), "publisher commits after the prune's locked boundary without a gap");
                assertEquals(1, batchCount(account));
                Reply after = pull(account, initialBoundary, 100);
                assertEquals(410, after.status(), after.body());
                String retainedCursor = pull(account, null, 100).json().path("nextCursor").asText();
                Reply retained = pull(account, retainedCursor, 100);
                assertEquals(200, retained.status(), retained.body());
                assertEquals(0, retained.json().path("batches").size());
                assertEquals(1, jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=? and cursor=0", Integer.class, account.id()),
                        "cleanup retains the old cursor registry; no token invalidation by deletion");
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            dropAdvisoryGate("task8b_prune_advisory_gate");
        }
    }

    private void createAdvisoryGate(String trigger, UUID accountId) {
        jdbc.execute("create function " + trigger + "() returns trigger language plpgsql as $$ begin "
                + "if NEW.account_id='" + accountId + "'::uuid then perform pg_advisory_xact_lock(" + PRUNE_LOCK + "::bigint); end if; "
                + "return NEW; end $$");
        jdbc.execute("create trigger " + trigger + " before update of pruned_through on account_sync_state "
                + "for each row execute function " + trigger + "()");
    }

    private void dropAdvisoryGate(String trigger) {
        jdbc.execute("drop trigger if exists " + trigger + " on account_sync_state");
        jdbc.execute("drop function if exists " + trigger + "()");
    }

    private void awaitSqlLock(String sqlFragment, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where pid<>pg_backend_pid() and datname=current_database() and wait_event_type='Lock' "
                    + "and lower(query) like ?", Integer.class, "%" + sqlFragment.toLowerCase() + "%");
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail("PostgreSQL did not report the expected lock wait for " + sqlFragment);
    }

    @Test
    void missingRequiredBatchHistoryFailsClosedWithoutAdvancingPastTheGap() {
        setServiceTime(Instant.parse("2035-06-01T00:00:00Z"));
        Account account = account();
        createExercise(account, "before deliberate gap");
        createExercise(account, "deliberate missing batch");
        createExercise(account, "after deliberate gap");
        for (long cursor = 1; cursor <= 3; cursor++) setCommitTime(account, cursor, Instant.parse("2030-01-01T00:00:00Z"));
        assertEquals(1, jdbc.update("delete from sync_changes where account_id=? and cursor=2", account.id()));
        assertEquals(1, jdbc.update("delete from sync_change_batches where account_id=? and cursor=2", account.id()));

        try {
            assertThrows(RuntimeException.class, cleanup::pruneExpiredHistory);
            assertEquals(0, watermark(account));
            assertEquals(3, committed(account));
            assertEquals(2, batchCount(account));
            assertEquals(2, changeCount(account), "the valid prefix was rolled back with the corrupt account transaction");
        } finally {
            jdbc.update("update accounts set deleted_at=clock_timestamp() where id=?", account.id());
        }
    }

    @Test
    void onlyEligibleAccountHeadsEnterCandidateBudgetAndBusyAccountsDoNotBlockOtherWork() {
        setServiceTime(Instant.parse("2035-01-01T00:00:00Z"));
        Account blockedByFreshHead = account();
        Account eligible = account();
        createExercise(blockedByFreshHead, "fresh leading batch");
        createExercise(blockedByFreshHead, "old later batch");
        createExercise(eligible, "old eligible first batch");
        setCommitTime(blockedByFreshHead, 1, Instant.parse("2034-12-31T00:00:00Z"));
        setCommitTime(blockedByFreshHead, 2, Instant.parse("2030-01-01T00:00:00Z"));
        setCommitTime(eligible, 1, Instant.parse("2030-01-01T00:00:00Z"));

        cleanup.pruneExpiredHistory();
        assertEquals(0, watermark(blockedByFreshHead), "a later old timestamp cannot pass the fresh first batch");
        assertEquals(2, committed(blockedByFreshHead));
        assertEquals(1, watermark(eligible), "an ineligible account head cannot starve eligible accounts");
    }

    @Test
    void accountBudgetIsFiniteBacklogProgressesAndDisabledAccountsAreExcluded() {
        setServiceTime(Instant.parse("2037-01-01T00:00:00Z"));
        Instant oldCommit = Instant.parse("2030-01-01T00:00:00Z");
        List<UUID> eligibleAccounts = new ArrayList<>();
        for (int index = 0; index < 101; index++) eligibleAccounts.add(insertCommittedDelete(oldCommit, false));
        UUID disabled = insertCommittedDelete(oldCommit, true);

        SyncHistoryPruningService.CleanupProgress first = cleanup.pruneExpiredHistory();
        assertEquals(100, first.candidatesConsidered(), "candidate query is bounded to the configured 100 accounts per tick");
        long prunedAfterOneTick = eligibleAccounts.stream().filter(id -> jdbc.queryForObject(
                "select pruned_through from account_sync_state where account_id=?", Long.class, id) == 1L).count();
        assertTrue(prunedAfterOneTick > 0 && prunedAfterOneTick <= 100);

        int ticks = 1;
        while (eligibleAccounts.stream().anyMatch(id -> watermark(new Account(id, "")) == 0) && ticks < 5) {
            SyncHistoryPruningService.CleanupProgress next = cleanup.pruneExpiredHistory();
            assertTrue(next.candidatesConsidered() <= 100);
            ticks++;
        }
        for (UUID id : eligibleAccounts) assertEquals(1, watermark(new Account(id, "")));
        assertEquals(0, watermark(new Account(disabled, "")), "disabled accounts are never selected for sync-history pruning");
        assertEquals(1, batchCount(new Account(disabled, "")));
    }

    private UUID insertCommittedDelete(Instant committedAt, boolean disabled) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into accounts(id,deleted_at) values(?,?)", accountId, disabled ? Timestamp.from(Instant.now()) : null);
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("update account_sync_state set committed_cursor=1 where account_id=?", accountId);
        jdbc.update("insert into sync_change_batches(account_id,cursor,batch_id,committed_at) values(?,1,?,?)",
                accountId, UUID.randomUUID(), Timestamp.from(committedAt));
        jdbc.update("insert into sync_changes(account_id,cursor,position,aggregate_type,aggregate_id,operation,revision,payload,expires_at) "
                        + "values(?,1,0,'customExercise',?,'delete',1,null,clock_timestamp()+interval '1 day')",
                accountId, aggregateId);
        return accountId;
    }

    @Test
    void boundedPerAccountBatchBudgetDrainsHistoryAcrossTicks() {
        setServiceTime(Instant.parse("2036-01-01T00:00:00Z"));
        Account account = account();
        int total = 501;
        for (int start = 0; start < total; start += 100) publishUniqueCustomMutations(account, start, Math.min(100, total - start));
        assertEquals(total, committed(account));
        setCommitTimesBeforeCutoff(account, Instant.parse("2030-01-01T00:00:00Z"));

        SyncHistoryPruningService.CleanupProgress firstTick = cleanup.pruneExpiredHistory();
        assertEquals(500, watermark(account), "one account is bounded to 500 complete batches per tick");
        assertEquals(1, batchCount(account));
        assertEquals(1, changeCount(account));
        assertEquals(501, committed(account));
        SyncHistoryPruningService.CleanupProgress secondTick = cleanup.pruneExpiredHistory();
        assertEquals(501, watermark(account));
        assertEquals(0, batchCount(account));
        assertEquals(0, changeCount(account));
        assertTrue(firstTick.batchesPruned() >= 500);
        assertTrue(secondTick.batchesPruned() >= 1);
        assertEquals(total, jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, account.id()),
                "pruning only removes the sync log, not account-lifetime replay evidence");
    }

    private void publishUniqueCustomMutations(Account account, int first, int count) {
        List<String> mutations = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            String root = id();
            int index = first + offset;
            mutations.add("{\"mutationId\":\"" + id() + "\",\"type\":\"customExercise.upsert\","
                    + "\"aggregateType\":\"customExercise\",\"aggregateId\":\"" + root + "\",\"localGeneration\":1,"
                    + "\"payload\":{\"id\":\"" + root + "\",\"name\":\"batch-limit-" + index + "\"}}");
        }
        Reply reply = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" + String.join(",", mutations) + "]}", null);
        assertEquals(200, reply.status(), reply.body());
        assertEquals(count, reply.json().path("results").size());
        for (JsonNode outcome : reply.json().path("results")) assertEquals("accepted", outcome.path("status").asText());
    }

    private void setCommitTimesBeforeCutoff(Account account, Instant timestamp) {
        assertEquals(501, jdbc.update("update sync_change_batches set committed_at=? where account_id=?", Timestamp.from(timestamp), account.id()));
    }

    @Test
    void pruningDoesNotChangePopulatedLiveAggregateRelationshipsChildrenOrActiveState() {
        Instant serviceNow = Instant.now();
        setServiceTime(serviceNow);
        Account account = account();
        List<UUID> muscles = jdbc.query("select id from muscle_groups order by id limit 2",
                (rs, row) -> rs.getObject(1, UUID.class));
        UUID equipment = jdbc.queryForObject("select id from equipment order by id limit 1", UUID.class);
        String exerciseId = id();
        String exerciseInput = "{\"id\":\"" + exerciseId + "\",\"name\":\"protected exercise\","
                + "\"primaryMuscleGroupId\":\"" + muscles.get(0) + "\",\"secondaryMuscleGroupIds\":[\"" + muscles.get(1)
                + "\"],\"equipmentId\":\"" + equipment + "\",\"equipmentNumItems\":2,\"category\":\"strength\","
                + "\"difficultyLevel\":\"intermediate\",\"forceType\":\"push\",\"mechanics\":\"compound\","
                + "\"instructions\":[\"retain first\",\"retain second\"]}";
        Reply exercise = request(account, "POST", "/v1/exercises", exerciseInput, id());
        assertEquals(201, exercise.status(), exercise.body());

        String routineId = id(), routineEntry = id(), routineSet = id();
        assertEquals(201, request(account, "POST", "/v1/routines", "{\"id\":\"" + routineId + "\"}", id()).status());
        String routineInput = "{\"id\":\"" + routineId + "\",\"name\":\"protected routine\",\"isDraft\":true,\"exercises\":[{"
                + "\"id\":\"" + routineEntry + "\",\"exerciseId\":\"" + exerciseId + "\",\"position\":0,"
                + "\"restDurationSeconds\":75,\"sets\":[{\"id\":\"" + routineSet
                + "\",\"position\":0,\"repetitions\":null,\"weightKg\":0}]}]}";
        assertEquals(200, request(account, "PUT", "/v1/routines/" + routineId, routineInput, id()).status());

        String workoutId = startWorkout(account, "protected active workout");
        String workoutEntry = id(), workoutSet = id();
        String workoutInput = "{\"id\":\"" + workoutId + "\",\"name\":\"protected active workout\","
                + "\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{"
                + "\"id\":\"" + workoutEntry + "\",\"exerciseId\":\"" + exerciseId + "\",\"position\":0,"
                + "\"notes\":\"preserve notes\",\"restDurationSeconds\":90,\"sets\":[{\"id\":\"" + workoutSet
                + "\",\"position\":0,\"repetitions\":8,\"weightKg\":12.5,\"isCompleted\":true,\"restSeconds\":42}]}]}";
        assertEquals(200, request(account, "PUT", "/v1/workouts/" + workoutId, workoutInput, id()).status());
        assertEquals(2, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=?", Integer.class, UUID.fromString(exerciseId)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=? and muscle_group_id=? and type='primary'",
                Integer.class, UUID.fromString(exerciseId), muscles.get(0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=? and muscle_group_id=? and type='secondary'",
                Integer.class, UUID.fromString(exerciseId), muscles.get(1)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_equipment where exercise_id=?", Integer.class, UUID.fromString(exerciseId)));
        assertEquals(1, jdbc.queryForObject("select count(*) from routine_sets s join routine_exercises e on e.id=s.routine_exercise_id "
                + "where e.routine_id=?", Integer.class, UUID.fromString(routineId)));
        assertEquals(1, jdbc.queryForObject("select count(*) from workout_sets s join workout_exercises e on e.id=s.workout_exercise_id "
                + "where e.workout_id=?", Integer.class, UUID.fromString(workoutId)));

        long publishedThrough = committed(account);
        String before = protectedDomainState(account);
        assertEquals(publishedThrough, jdbc.update("update sync_change_batches set committed_at=? where account_id=?",
                Timestamp.from(serviceNow.minus(91, ChronoUnit.DAYS)), account.id()));
        cleanup.pruneExpiredHistory();

        assertEquals(publishedThrough, watermark(account));
        assertEquals(publishedThrough, committed(account));
        assertEquals(before, protectedDomainState(account),
                "pruning log history must leave live relationships, ordered children, active state and account-lifetime metadata byte-for-byte unchanged");
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=? and muscle_group_id=? and type='primary'",
                Integer.class, UUID.fromString(exerciseId), muscles.get(0)));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercise_muscle_groups where exercise_id=? and muscle_group_id=? and type='secondary'",
                Integer.class, UUID.fromString(exerciseId), muscles.get(1)));
        assertEquals("active", request(account, "GET", "/v1/workouts/" + workoutId, null, null).json().path("status").asText());
        assertEquals("protected routine", request(account, "GET", "/v1/routines/" + routineId, null, null).json().path("name").asText());
    }

    private String protectedDomainState(Account account) {
        String sql = "with target as (select ?::uuid as owner_id) select jsonb_build_object("
                + "'exercises',(select jsonb_agg(to_jsonb(e) order by e.id) from exercises e,target t where e.owner_id=t.owner_id),"
                + "'exerciseMuscles',(select jsonb_agg(to_jsonb(m) order by m.exercise_id,m.muscle_group_id,m.type) from exercise_muscle_groups m "
                + "join exercises e on e.id=m.exercise_id,target t where e.owner_id=t.owner_id),"
                + "'exerciseEquipment',(select jsonb_agg(to_jsonb(eq) order by eq.exercise_id,eq.equipment_id) from exercise_equipment eq "
                + "join exercises e on e.id=eq.exercise_id,target t where e.owner_id=t.owner_id),"
                + "'workouts',(select jsonb_agg(to_jsonb(w) order by w.id) from workouts w,target t where w.owner_id=t.owner_id),"
                + "'workoutEntries',(select jsonb_agg(to_jsonb(e) order by e.workout_id,e.position) from workout_exercises e "
                + "join workouts w on w.id=e.workout_id,target t where w.owner_id=t.owner_id),"
                + "'workoutSets',(select jsonb_agg(to_jsonb(s) order by e.workout_id,e.position,s.position) from workout_sets s "
                + "join workout_exercises e on e.id=s.workout_exercise_id join workouts w on w.id=e.workout_id,target t where w.owner_id=t.owner_id),"
                + "'routines',(select jsonb_agg(to_jsonb(r) order by r.id) from routines r,target t where r.owner_id=t.owner_id),"
                + "'routineEntries',(select jsonb_agg(to_jsonb(e) order by e.routine_id,e.position) from routine_exercises e "
                + "join routines r on r.id=e.routine_id,target t where r.owner_id=t.owner_id),"
                + "'routineSets',(select jsonb_agg(to_jsonb(s) order by e.routine_id,e.position,s.position) from routine_sets s "
                + "join routine_exercises e on e.id=s.routine_exercise_id join routines r on r.id=e.routine_id,target t where r.owner_id=t.owner_id),"
                + "'rootReservations',(select jsonb_agg(to_jsonb(r) order by r.aggregate_type,r.aggregate_id) from root_id_reservations r,target t where r.owner_id=t.owner_id),"
                + "'childReservations',(select jsonb_agg(to_jsonb(c) order by c.family,c.child_id) from child_id_reservations c,target t where c.owner_id=t.owner_id),"
                + "'processedMutations',(select jsonb_agg(to_jsonb(p) order by p.mutation_id) from processed_mutations p,target t where p.owner_id=t.owner_id),"
                + "'resourceMutations',(select jsonb_agg(to_jsonb(m) order by m.idempotency_key) from resource_mutations m,target t where m.owner_id=t.owner_id),"
                + "'credentials',(select jsonb_agg(jsonb_build_object('fingerprint',encode(c.fingerprint,'hex'),'kind',c.kind,"
                + "'issued_at',c.issued_at,'expires_at',c.expires_at,'revoked_at',s.revoked_at) order by c.fingerprint) "
                + "from auth_credentials c join auth_sessions s on s.id=c.session_id,target t where s.account_id=t.owner_id))::text from target";
        return jdbc.queryForObject(sql, String.class, account.id());
    }

    @Test
    void acceptedAndRejectedSyncAndDirectReplaySurvivePruningWithoutRestoringOldWinner() throws Exception {
        setServiceTime(Instant.parse("2034-01-01T00:00:00Z"));
        Account account = account();
        UUID firstMutation = UUID.randomUUID();
        String root = id();
        JsonNode accepted = JSON.readTree(pushCustom(account, firstMutation, root, "original sync value"))
                .path("results").get(0);
        assertEquals("accepted", accepted.path("status").asText());
        JsonNode originalBatch = accepted.path("batch");
        Account otherAccount = account();
        String otherRoot = id();
        JsonNode otherAccepted = JSON.readTree(pushCustom(otherAccount, firstMutation, otherRoot, "other account same key"))
                .path("results").get(0);
        assertEquals("accepted", otherAccepted.path("status").asText(), "sync mutation identity is account-scoped");
        JsonNode otherOriginalBatch = otherAccepted.path("batch");

        UUID rejectedMutation = UUID.randomUUID();
        String missingWorkout = id();
        String rejectedRequest = "{\"mutationId\":\"" + rejectedMutation + "\",\"type\":\"workout.finish\","
                + "\"aggregateType\":\"workout\",\"aggregateId\":\"" + missingWorkout + "\",\"localGeneration\":1,"
                + "\"payload\":{\"finishedAt\":\"2025-01-01T00:00:00Z\"}}";
        Reply rejectedResponse = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" + rejectedRequest + "]}", null);
        assertEquals(200, rejectedResponse.status(), rejectedResponse.body());
        JsonNode rejected = rejectedResponse.json().path("results").get(0);
        assertEquals("rejected", rejected.path("status").asText());
        String rejectionCode = rejected.path("errorCode").asText();

        String tombstonedRoot = createExercise(account, "tombstone survives log pruning");
        String workoutRoot = startWorkout(account, "child reservations survive");
        String workoutEntry = id(), workoutSet = id();
        String workoutInput = "{\"id\":\"" + workoutRoot + "\",\"name\":\"child reservations survive\","
                + "\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{"
                + "\"id\":\"" + workoutEntry + "\",\"exerciseId\":\"" + root + "\",\"position\":0,"
                + "\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + workoutSet
                + "\",\"position\":0,\"repetitions\":null,\"weightKg\":null,\"isCompleted\":false,\"restSeconds\":null}]}]}";
        Reply updatedWorkout = request(account, "PUT", "/v1/workouts/" + workoutRoot, workoutInput, id());
        assertEquals(200, updatedWorkout.status(), updatedWorkout.body());
        assertEquals(204, request(account, "DELETE", "/v1/workouts/" + workoutRoot, null, id()).status());
        int childReservationsBefore = jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' "
                + "and root_id=?", Integer.class, UUID.fromString(workoutRoot));
        assertEquals(3, childReservationsBefore, "root, entry, and set identities are reserved");
        Timestamp credentialExpiryBefore = jdbc.queryForObject("select c.expires_at from auth_credentials c "
                + "join auth_sessions s on s.id=c.session_id where s.account_id=? and c.kind='access'", Timestamp.class, account.id());

        Reply deleted = request(account, "DELETE", "/v1/exercises/" + tombstonedRoot, null, id());
        assertEquals(204, deleted.status(), deleted.body());
        assertEquals(1, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='customExercise' "
                + "and aggregate_id=? and owner_id=?", Integer.class, UUID.fromString(tombstonedRoot), account.id()));

        Reply becameEligible = request(account, "POST", "/v1/workouts", "{\"id\":\"" + missingWorkout
                + "\",\"name\":\"eligible after rejection\",\"startedAt\":\"2025-01-01T00:00:00Z\"}", id());
        assertEquals(201, becameEligible.status(), becameEligible.body());
        String eligibleEntry = id(), eligibleSet = id();
        String eligibleWorkout = "{\"id\":\"" + missingWorkout + "\",\"name\":\"eligible after rejection\","
                + "\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{"
                + "\"id\":\"" + eligibleEntry + "\",\"exerciseId\":\"" + root + "\",\"position\":0,"
                + "\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"" + eligibleSet
                + "\",\"position\":0,\"repetitions\":8,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":null}]}]}";
        Reply eligibleEdit = request(account, "PUT", "/v1/workouts/" + missingWorkout, eligibleWorkout, id());
        assertEquals(200, eligibleEdit.status(), eligibleEdit.body());
        long eligibleWorkoutRevision = jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(missingWorkout));

        String directAcceptedId = id();
        String directAcceptedBody = "{\"id\":\"" + directAcceptedId + "\",\"name\":\"accepted resource survives pruning\"}";
        Reply directAccepted = request(account, "POST", "/v1/exercises", directAcceptedBody, firstMutation.toString());
        assertEquals(201, directAccepted.status(), directAccepted.body());
        String directWinnerBody = "{\"id\":\"" + directAcceptedId + "\",\"name\":\"later direct resource winner\"}";
        Reply directWinner = request(account, "PUT", "/v1/exercises/" + directAcceptedId, directWinnerBody, id());
        assertEquals(200, directWinner.status(), directWinner.body());
        long directWinnerRevision = jdbc.queryForObject("select revision from exercises where owner_id=? and id=?",
                Long.class, account.id(), UUID.fromString(directAcceptedId));

        String directTarget = id();
        String directBody = "{\"id\":\"" + directTarget + "\",\"name\":\"direct retry stays rejected\"}";
        String directKey = id();
        Reply directRejected = request(account, "PUT", "/v1/exercises/" + directTarget, directBody, directKey);
        assertEquals(404, directRejected.status(), directRejected.body());
        assertEquals("not_found", directRejected.json().path("errorCode").asText());

        pushCustom(account, UUID.randomUUID(), root, "later winner");
        Reply directBecameEligible = request(account, "POST", "/v1/exercises", directBody, id());
        assertEquals(201, directBecameEligible.status(), directBecameEligible.body());
        long directTargetRevision = jdbc.queryForObject("select revision from exercises where owner_id=? and id=?",
                Long.class, account.id(), UUID.fromString(directTarget));

        for (long cursor = 1; cursor <= committed(account); cursor++)
            setCommitTime(account, cursor, Instant.parse("2033-01-01T00:00:00Z"));
        cleanup.pruneExpiredHistory();
        assertEquals(committed(account), watermark(account));
        assertEquals(0, batchCount(account));
        assertEquals(0, changeCount(account));
        assertEquals(1, jdbc.queryForObject("select count(*) from exercises where owner_id=? and id=? and deleted_at is not null",
                Integer.class, account.id(), UUID.fromString(tombstonedRoot)));
        assertEquals(1, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='customExercise' "
                + "and aggregate_id=? and owner_id=?", Integer.class, UUID.fromString(tombstonedRoot), account.id()));
        assertEquals(childReservationsBefore, jdbc.queryForObject("select count(*) from child_id_reservations where family='workout' "
                + "and root_id=?", Integer.class, UUID.fromString(workoutRoot)));
        assertEquals(credentialExpiryBefore, jdbc.queryForObject("select c.expires_at from auth_credentials c "
                + "join auth_sessions s on s.id=c.session_id where s.account_id=? and c.kind='access'", Timestamp.class, account.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from root_id_reservations where aggregate_type='workout' "
                + "and aggregate_id=? and owner_id=?", Integer.class, UUID.fromString(workoutRoot), account.id()));
        assertEquals(409, request(account, "POST", "/v1/workouts", "{\"id\":\"" + workoutRoot
                + "\",\"name\":\"cannot resurrect\",\"startedAt\":\"2025-01-01T00:00:00Z\"}", id()).status());
        Reply resurrection = request(account, "POST", "/v1/exercises", "{\"id\":\"" + tombstonedRoot
                + "\",\"name\":\"cannot resurrect\"}", id());
        assertEquals(409, resurrection.status(), resurrection.body());

        Reply syncReplay = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" +
                "{\"mutationId\":\"" + firstMutation + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\","
                        + "\"aggregateId\":\"" + root + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + root + "\",\"name\":\"original sync value\"}}]}", null);
        assertEquals(200, syncReplay.status(), syncReplay.body());
        JsonNode replayed = syncReplay.json().path("results").get(0);
        assertEquals("duplicate", replayed.path("status").asText());
        assertEquals(originalBatch, replayed.path("batch"), "all original batch fields and ordered change arrays survive pruning");
        assertEquals("later winner", request(account, "GET", "/v1/exercises/" + root, null, null).json().path("name").asText());
        Reply otherReplay = request(otherAccount, "POST", "/v1/sync/push", "{\"mutations\":["
                + "{\"mutationId\":\"" + firstMutation + "\",\"type\":\"customExercise.upsert\",\"aggregateType\":\"customExercise\","
                + "\"aggregateId\":\"" + otherRoot + "\",\"localGeneration\":1,\"payload\":{\"id\":\"" + otherRoot
                + "\",\"name\":\"other account same key\"}}]}", null);
        assertEquals("duplicate", otherReplay.json().path("results").get(0).path("status").asText());
        assertEquals(otherOriginalBatch, otherReplay.json().path("results").get(0).path("batch"));
        assertNotEquals(originalBatch, otherOriginalBatch, "account A's stored response is not replayed to account B");

        Reply rejectionReplay = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" + rejectedRequest + "]}", null);
        assertEquals("duplicate", rejectionReplay.json().path("results").get(0).path("status").asText());
        assertEquals(rejectionCode, rejectionReplay.json().path("results").get(0).path("errorCode").asText());
        assertEquals("active", request(account, "GET", "/v1/workouts/" + missingWorkout, null, null).json().path("status").asText());
        assertEquals(eligibleWorkoutRevision, jdbc.queryForObject("select revision from workouts where id=?", Long.class, UUID.fromString(missingWorkout)),
                "replaying the original rejection leaves the later-eligible active workout unchanged");
        String changedRequest = rejectedRequest.replace("2025-01-01", "2025-01-02");
        Reply changed = request(account, "POST", "/v1/sync/push", "{\"mutations\":[" + changedRequest + "]}", null);
        assertEquals("mutation_id_reused", changed.json().path("results").get(0).path("errorCode").asText());

        Reply acceptedResourceReplay = request(account, "POST", "/v1/exercises", directAcceptedBody, firstMutation.toString());
        assertEquals(201, acceptedResourceReplay.status());
        assertEquals(directAccepted.body(), acceptedResourceReplay.body(), "accepted resource response and sync-namespace key coexist durably");
        Reply currentDirectWinner = request(account, "GET", "/v1/exercises/" + directAcceptedId, null, null);
        assertEquals("later direct resource winner", currentDirectWinner.json().path("name").asText(),
                "replaying the older accepted resource operation does not restore its stale value");
        assertEquals(directWinnerRevision, jdbc.queryForObject("select revision from exercises where owner_id=? and id=?",
                Long.class, account.id(), UUID.fromString(directAcceptedId)));
        assertEquals(409, request(account, "POST", "/v1/exercises", directAcceptedBody.replace("accepted resource", "changed resource"),
                firstMutation.toString()).status());

        Reply directReplay = request(account, "PUT", "/v1/exercises/" + directTarget, directBody, directKey);
        assertEquals(404, directReplay.status());
        assertEquals(directRejected.body(), directReplay.body(), "direct resource rejection status/body survives pruning");
        Reply unchangedDirectRoot = request(account, "GET", "/v1/exercises/" + directTarget, null, null);
        assertEquals("direct retry stays rejected", unchangedDirectRoot.json().path("name").asText(),
                "a rejected retry cannot re-execute after its target becomes eligible");
        assertEquals(directTargetRevision, jdbc.queryForObject("select revision from exercises where owner_id=? and id=?",
                Long.class, account.id(), UUID.fromString(directTarget)));
        assertEquals(409, request(account, "PUT", "/v1/exercises/" + directTarget,
                directBody.replace("direct retry stays rejected", "changed request"), directKey).status());
        assertTrue(jdbc.queryForObject("select count(*) from resource_mutations where owner_id=?", Integer.class, account.id()) >= 1);
        assertTrue(jdbc.queryForObject("select count(*) from processed_mutations where owner_id=?", Integer.class, account.id()) >= 3);
        assertTrue(jdbc.queryForObject("select count(*) from sync_cursor_tokens where account_id=?", Integer.class, account.id()) >= 1);
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_credentials c join auth_sessions s on s.id=c.session_id "
                + "where s.account_id=? and c.kind='access'", Integer.class, account.id()));
    }
}
