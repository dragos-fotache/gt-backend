package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.account.AccountExportHooks;
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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * AC9a.5: coherent reads and deletion-ready cancellation.
 *
 * <p>All assertions compare wire output against hand-authored intended values. A concurrent
 * writer may commit before or during an export; repeatable-read semantics admit exactly two
 * valid outcomes (the full before-state or the full after-state) and never a mixture.
 * Controlled disablement uses isolated-database fixtures; real deletion-endpoint races are
 * re-verified in later slices.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountExportCancellationHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> "export-cancel-audience");
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ControllableHooks exportHooks;
    @MockitoBean
    GoogleTokenVerifier verifier;

    static final class ControllableHooks implements AccountExportHooks {
        final AtomicReference<GateControl> control = new AtomicReference<>(GateControl.open());

        @Override
        public void afterSnapshotEstablished() {
            control.get().arrive(GatePoint.AFTER_SNAPSHOT);
        }

        @Override
        public void afterConsumptionCommitted() {
            control.get().arrive(GatePoint.AFTER_CONSUMPTION);
        }

        @Override
        public void beforeFirstByte() {
            control.get().arrive(GatePoint.BEFORE_FIRST_BYTE);
        }

        @Override
        public void beforeSegment(int elementIndex) {
        }

        @Override
        public void beforeByteSegment(long ordinal) {
            control.get().arriveByteSegment(ordinal);
        }

        @Override
        public void beforeFinalClosure() {
            control.get().arrive(GatePoint.BEFORE_FINAL_CLOSURE);
        }

        @Override
        public void beforeSuccessFlush() {
            control.get().arrive(GatePoint.BEFORE_SUCCESS_FLUSH);
        }
    }

    enum GatePoint {
        AFTER_SNAPSHOT, AFTER_CONSUMPTION, BEFORE_FIRST_BYTE, BEFORE_FINAL_CLOSURE, BEFORE_SUCCESS_FLUSH
    }

    static final class GateControl {
        private final java.util.EnumSet<GatePoint> watched;
        private final java.util.concurrent.ConcurrentMap<GatePoint, CountDownLatch> entered =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<RuntimeException> fault = new AtomicReference<>();
        private final AtomicReference<Long> byteSegmentTarget = new AtomicReference<>();
        private final CountDownLatch byteSegmentEntered = new CountDownLatch(1);

        private GateControl(java.util.EnumSet<GatePoint> watched) {
            this.watched = watched;
        }

        static GateControl open() {
            return new GateControl(java.util.EnumSet.noneOf(GatePoint.class));
        }

        static GateControl watch(GatePoint... points) {
            GateControl control = new GateControl(java.util.EnumSet.noneOf(GatePoint.class));
            control.watched.addAll(java.util.List.of(points));
            return control;
        }

        void watchByteSegment(long ordinal) {
            byteSegmentTarget.set(ordinal);
        }

        void failWith(RuntimeException fault) {
            this.fault.set(fault);
        }

        void awaitEntry(GatePoint point) {
            try {
                CountDownLatch latch = entered.computeIfAbsent(point, ignored -> new CountDownLatch(1));
                assertTrue(latch.await(60, TimeUnit.SECONDS), "gate was never reached: " + point);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void awaitByteSegment() {
            try {
                assertTrue(byteSegmentEntered.await(60, TimeUnit.SECONDS), "byte segment gate was never reached");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void release() {
            release.countDown();
        }

        private void arrive(GatePoint point) {
            RuntimeException failure = fault.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            if (!watched.contains(point)) {
                return;
            }
            entered.computeIfAbsent(point, ignored -> new CountDownLatch(1)).countDown();
            try {
                assertTrue(release.await(60, TimeUnit.SECONDS), "gate was never released: " + point);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            RuntimeException late = fault.getAndSet(null);
            if (late != null) {
                throw late;
            }
        }

        private void arriveByteSegment(long ordinal) {
            Long target = byteSegmentTarget.get();
            if (target != null && ordinal >= target) {
                byteSegmentTarget.set(null);
                byteSegmentEntered.countDown();
                try {
                    assertTrue(release.await(60, TimeUnit.SECONDS), "byte segment gate was never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
    }

    @TestConfiguration
    static class CancellationHooksConfiguration {
        @Bean
        @Primary
        ControllableHooks cancellationHooks() {
            return new ControllableHooks();
        }
    }

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

    private Reply post(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
        Reply challenge = post("/v1/auth/google/challenge", "{}");
        assertEquals(200, challenge.status, challenge.body);
        lenient().when(verifier.verify(token, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(subject, null));
        Reply grant = postWithKey("/v1/auth/google",
                "{\"challengeId\":\"" + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + token + "\"}");
        assertEquals(200, grant.status, grant.body);
        BoundAccount account = new BoundAccount(UUID.fromString(grant.node().path("account").path("id").asText()), subject,
                grant.node().path("accessToken").asText());
        accountIdToSubject.put(account.accountId.toString(), subject);
        return account;
    }

    private Reply postWithKey(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new AssertionError("HTTP POST failed: " + path, e);
        }
    }

    private Reply exportCall(String accountId, String tokenName) {
        Reply challenge = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        assertEquals(200, challenge.status, challenge.body);
        lenient().when(verifier.verify(tokenName, challenge.node().path("nonce").asText()))
                .thenReturn(new Verified(accountIdToSubject.get(accountId), null));
        return post("/v1/account/export",
                "{\"accountId\":\"" + accountId + "\",\"challengeId\":\""
                        + challenge.node().path("challengeId").asText() + "\",\"idToken\":\"" + tokenName + "\"}");
    }

    private final java.util.Map<String, String> accountIdToSubject = new java.util.concurrent.ConcurrentHashMap<>();

    private BoundAccount provisionTracked(String subject, String token) {
        return provision(subject, token);
    }

    private boolean challengeConsumed(String challengeId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT consumed_at IS NOT NULL FROM auth_challenges WHERE id=?",
                rs -> rs.next() && rs.getBoolean(1), UUID.fromString(challengeId)));
    }

    private String setupCoherenceData(BoundAccount account, String customName, String routineName) {
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"" + customName + "\"}").status);
        String routine = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status);
        String entry = UUID.randomUUID().toString();
        String set = UUID.randomUUID().toString();
        Reply put = authed(account.accessToken, "PUT", "/v1/routines/" + routine,
                "{\"id\":\"" + routine + "\",\"name\":\"" + routineName + "\",\"isDraft\":true,\"exercises\":["
                        + "{\"id\":\"" + entry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,"
                        + "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + set
                        + "\",\"position\":0,\"repetitions\":5,\"weightKg\":10}]}]}");
        assertEquals(200, put.status, put.body);
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Coherence workout\",\"startedAt\":\"2026-09-04T06:00:00Z\"}").status);
        return routine;
    }

    @Test
    void committedRenameAndDeleteBeforeExportAppearExactly() {
        BoundAccount account = provisionTracked("coherent-subject", "coherent-signup");
        String routine = setupCoherenceData(account, "Coherence base", "Coherence routine");
        String draftExtra = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + draftExtra + "\"}").status);

        // Writer commits before the export starts: the export must equal the after-state exactly.
        assertEquals(200, authed(account.accessToken, "PUT", "/v1/routines/" + routine,
                "{\"id\":\"" + routine + "\",\"name\":\"Renamed routine\",\"isDraft\":true,\"exercises\":[]}").status);
        assertEquals(204, authed(account.accessToken, "DELETE", "/v1/routines/" + draftExtra, null).status);

        Reply reply = exportCall(account.accountId.toString(), "coherent-token");
        assertEquals(200, reply.status, reply.body);
        JsonNode root = reply.node();
        boolean renamedSeen = false;
        boolean deletedAbsent = true;
        for (JsonNode candidate : root.path("routines")) {
            if (routine.equals(candidate.path("id").asText())) {
                renamedSeen = "Renamed routine".equals(candidate.path("name").asText());
            }
            if (draftExtra.equals(candidate.path("id").asText())) {
                deletedAbsent = false;
            }
        }
        assertTrue(renamedSeen, "committed rename must be visible: " + reply.body);
        assertTrue(deletedAbsent, "committed delete must be visible: " + reply.body);
    }

    @Test
    void gatedWriterDuringExportKeepsExactBeforeView() throws Exception {
        BoundAccount account = provisionTracked("mixed-view-subject", "mixed-view-signup");
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"Before name\"}").status);
        String routine = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/routines", "{\"id\":\"" + routine + "\"}").status);
        String entry = UUID.randomUUID().toString();
        String set = UUID.randomUUID().toString();
        // Large routine note surface is not available, so widen through a big workout instead.
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"Wide workout\",\"startedAt\":\"2026-09-05T06:00:00Z\"}").status);
        StringBuilder children = new StringBuilder("[");
        for (int index = 0; index < 30; index++) {
            if (index > 0) {
                children.append(",");
            }
            children.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"exerciseId\":\"").append(custom)
                    .append("\",\"position\":").append(index).append(",\"notes\":\"")
                    .append("padding-").append(index).append("-".repeat(2000))
                    .append("\",\"restDurationSeconds\":10,\"sets\":[{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"position\":0,\"repetitions\":5,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":10}]}");
        }
        children.append("]");
        Reply put = authed(account.accessToken, "PUT", "/v1/routines/" + routine,
                "{\"id\":\"" + routine + "\",\"name\":\"View routine\",\"isDraft\":true,\"exercises\":["
                        + "{\"id\":\"" + entry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,"
                        + "\"restDurationSeconds\":0,\"sets\":[{\"id\":\"" + set
                        + "\",\"position\":0,\"repetitions\":5,\"weightKg\":10}]}]}");
        assertEquals(200, put.status, put.body);
        Reply workoutPut = authed(account.accessToken, "PUT", "/v1/workouts/" + workout,
                "{\"id\":\"" + workout + "\",\"name\":\"Wide workout\",\"status\":\"active\","
                        + "\"startedAt\":\"2026-09-05T06:00:00Z\",\"exercises\":" + children + "}");
        assertEquals(200, workoutPut.status, workoutPut.body);

        // Hold the export at its established snapshot (no write lock is held there), then commit
        // a rename, a child replacement, and a delete as separate accepted writes while it waits.
        String newEntry = UUID.randomUUID().toString();
        String newSet = UUID.randomUUID().toString();
        GateControl control = GateControl.watch(GatePoint.AFTER_SNAPSHOT);
        exportHooks.control.set(control);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
            String challengeId = proof.node().path("challengeId").asText();
            lenient().when(verifier.verify("mixed-view-token", proof.node().path("nonce").asText()))
                    .thenReturn(new Verified("mixed-view-subject", null));
            String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"mixed-view-token\"}";
            Future<Reply> pending = pool.submit(() -> post("/v1/account/export", body));
            control.awaitEntry(GatePoint.AFTER_SNAPSHOT);
            assertEquals(200, authed(account.accessToken, "PUT", "/v1/exercises/" + custom,
                    "{\"id\":\"" + custom + "\",\"name\":\"After name\"}").status);
            assertEquals(200, authed(account.accessToken, "PUT", "/v1/routines/" + routine,
                    "{\"id\":\"" + routine + "\",\"name\":\"Replaced routine\",\"isDraft\":true,\"exercises\":["
                            + "{\"id\":\"" + newEntry + "\",\"exerciseId\":\"" + custom + "\",\"position\":0,"
                            + "\"restDurationSeconds\":5,\"sets\":[{\"id\":\"" + newSet
                            + "\",\"position\":0,\"repetitions\":9,\"weightKg\":11}]}]}").status);
            assertEquals(204, authed(account.accessToken, "DELETE", "/v1/workouts/" + workout, null).status);
            control.release();
            Reply reply = pending.get(120, TimeUnit.SECONDS);
            assertEquals(200, reply.status, reply.body);
            JsonNode root = reply.node();

            // The export must equal the exact established before-view in every field and label.
            JsonNode seenCustom = null;
            for (JsonNode candidate : root.path("customExercises")) {
                if (custom.equals(candidate.path("id").asText())) {
                    seenCustom = candidate;
                }
            }
            assertNotNull(seenCustom, "snapshot export must still contain the custom exercise");
            assertEquals("Before name", seenCustom.path("name").asText());
            JsonNode seenRoutine = null;
            for (JsonNode candidate : root.path("routines")) {
                if (routine.equals(candidate.path("id").asText())) {
                    seenRoutine = candidate;
                }
            }
            assertNotNull(seenRoutine, "snapshot export must still contain the routine");
            assertEquals("View routine", seenRoutine.path("name").asText());
            assertEquals(entry, seenRoutine.path("exercises").get(0).path("id").asText());
            assertEquals(set, seenRoutine.path("exercises").get(0).path("sets").get(0).path("id").asText());
            assertEquals("Before name", seenRoutine.path("exercises").get(0).path("exerciseName").asText());
            JsonNode seenWorkout = null;
            for (JsonNode candidate : root.path("workouts")) {
                if (workout.equals(candidate.path("id").asText())) {
                    seenWorkout = candidate;
                }
            }
            assertNotNull(seenWorkout, "snapshot export must still contain the deleted workout");
            assertEquals(30, seenWorkout.path("exercises").size());
            assertEquals("Before name", seenWorkout.path("exercises").get(0).path("exerciseName").asText());

            // A fresh export proves the after-state with the same assertions inverted.
            Reply after = exportCall(account.accountId.toString(), "mixed-view-after");
            assertEquals(200, after.status, after.body);
            JsonNode afterRoot = after.node();
            for (JsonNode candidate : afterRoot.path("customExercises")) {
                if (custom.equals(candidate.path("id").asText())) {
                    assertEquals("After name", candidate.path("name").asText());
                }
            }
            boolean replacedSeen = false;
            for (JsonNode candidate : afterRoot.path("routines")) {
                if (routine.equals(candidate.path("id").asText())) {
                    replacedSeen = true;
                    assertEquals("Replaced routine", candidate.path("name").asText());
                    assertEquals(newEntry, candidate.path("exercises").get(0).path("id").asText());
                    assertEquals(newSet, candidate.path("exercises").get(0).path("sets").get(0).path("id").asText());
                }
            }
            assertTrue(replacedSeen, "fresh export must show the replaced routine child");
            for (JsonNode candidate : afterRoot.path("workouts")) {
                assertNotEquals(workout, candidate.path("id").asText(), "fresh export must omit the deleted workout");
            }
        } finally {
            exportHooks.control.set(GateControl.open());
            control.release();
            pool.shutdownNow();
        }
    }

    private String seedWideWorkout(BoundAccount account, String name, int exercises, int noteRepeat) {
        String custom = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/exercises",
                "{\"id\":\"" + custom + "\",\"name\":\"" + name + " base\"}").status);
        String workout = UUID.randomUUID().toString();
        assertEquals(201, authed(account.accessToken, "POST", "/v1/workouts",
                "{\"id\":\"" + workout + "\",\"name\":\"" + name + "\",\"startedAt\":\"2026-09-06T06:00:00Z\"}").status);
        StringBuilder children = new StringBuilder("[");
        for (int index = 0; index < exercises; index++) {
            if (index > 0) {
                children.append(",");
            }
            children.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"exerciseId\":\"").append(custom)
                    .append("\",\"position\":").append(index).append(",\"notes\":\"")
                    .append(name).append("-").append(index).append("-".repeat(noteRepeat))
                    .append("\",\"restDurationSeconds\":10,\"sets\":[{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"position\":0,\"repetitions\":5,\"weightKg\":10,\"isCompleted\":true,\"restSeconds\":10}]}");
        }
        children.append("]");
        assertEquals(200, authed(account.accessToken, "PUT", "/v1/workouts/" + workout,
                "{\"id\":\"" + workout + "\",\"name\":\"" + name + "\",\"status\":\"active\","
                        + "\"startedAt\":\"2026-09-06T06:00:00Z\",\"exercises\":" + children + "}").status);
        return workout;
    }

    private Reply exportWithProof(BoundAccount account, String tokenName) {
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        assertEquals(200, proof.status, proof.body);
        lenient().when(verifier.verify(tokenName, proof.node().path("nonce").asText()))
                .thenReturn(new Verified(account.subject, null));
        return post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\""
                        + proof.node().path("challengeId").asText() + "\",\"idToken\":\"" + tokenName + "\"}");
    }

    private static final class DripRead {
        byte[] bytes;
        Exception failure;
    }

    private DripRead dripRead(String body) {
        DripRead result = new DripRead();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/account/export"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> streaming = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, streaming.statusCode());
            byte[] chunk = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int next;
            while ((next = streaming.body().read(chunk)) != -1) {
                out.write(chunk, 0, next);
            }
            result.bytes = out.toByteArray();
        } catch (Exception e) {
            result.failure = e;
            result.bytes = null;
        }
        return result;
    }

    private void assertAbortedWithoutPartialSuccess(DripRead read, String challengeId) {
        assertTrue(challengeConsumed(challengeId), "admitted export must stay consumed after cancellation");
        assertNotNull(read.bytes, "aborted stream must still deliver its released prefix, got failure: " + read.failure);
        assertTrue(read.bytes.length > 0, "admitted export must have released bytes before aborting");
        String text = new String(read.bytes, StandardCharsets.UTF_8);
        int snippet = Math.min(200, text.length());
        String context = " bodyLen=" + read.bytes.length + " head=" + text.substring(0, snippet)
                + " tail=" + text.substring(text.length() - snippet);
        assertFalse(text.contains("\"errorCode\""), "post-byte abort must not append an error envelope:" + context);
        try {
            JSON.readTree(read.bytes);
            fail("cancelled download must not parse as a complete success");
        } catch (java.io.IOException expected) {
            // Incomplete JSON is the required failure shape; fail() throws AssertionError, never caught here.
        }
    }

    @Test
    void disableAfterAdmissionBeforeFirstByteReleasesNothing() throws Exception {
        BoundAccount account = provisionTracked("prebyte-subject", "prebyte-signup");
        seedWideWorkout(account, "Prebyte", 5, 200);
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        String challengeId = proof.node().path("challengeId").asText();
        lenient().when(verifier.verify("prebyte-token", proof.node().path("nonce").asText()))
                .thenReturn(new Verified("prebyte-subject", null));
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"prebyte-token\"}";
        GateControl control = GateControl.watch(GatePoint.BEFORE_FIRST_BYTE);
        exportHooks.control.set(control);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> pending = pool.submit(() -> post("/v1/account/export", body));
            control.awaitEntry(GatePoint.BEFORE_FIRST_BYTE);
            jdbc.update("UPDATE accounts SET deleted_at=clock_timestamp() WHERE id=?", account.accountId);
            control.release();
            Reply reply = pending.get(120, TimeUnit.SECONDS);
            assertEquals(404, reply.status, reply.body);
            assertEquals("not_found", reply.node().path("errorCode").asText());
            assertFalse(reply.body.contains("customExercises"), "disabled export must release no export data");
            assertTrue(challengeConsumed(challengeId), "admitted export stays consumed even with no bytes");
        } finally {
            jdbc.update("UPDATE accounts SET deleted_at=NULL WHERE id=?", account.accountId);
            exportHooks.control.set(GateControl.open());
            control.release();
            pool.shutdownNow();
        }
        Reply retried = exportWithProof(account, "prebyte-fresh");
        assertEquals(200, retried.status, retried.body);
    }

    @Test
    void disableMidLargeSingleRootAbortsWithoutPartialSuccess() throws Exception {
        BoundAccount account = provisionTracked("midroot-subject", "midroot-signup");
        seedWideWorkout(account, "Midroot", 40, 3000);
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        String challengeId = proof.node().path("challengeId").asText();
        lenient().when(verifier.verify("midroot-token", proof.node().path("nonce").asText()))
                .thenReturn(new Verified("midroot-subject", null));
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"midroot-token\"}";
        GateControl control = GateControl.open();
        control.watchByteSegment(10);
        exportHooks.control.set(control);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DripRead> pending = pool.submit(() -> dripRead(body));
            control.awaitByteSegment();
            jdbc.update("UPDATE accounts SET deleted_at=clock_timestamp() WHERE id=?", account.accountId);
            control.release();
            assertAbortedWithoutPartialSuccess(pending.get(180, TimeUnit.SECONDS), challengeId);
        } finally {
            jdbc.update("UPDATE accounts SET deleted_at=NULL WHERE id=?", account.accountId);
            exportHooks.control.set(GateControl.open());
            control.release();
            pool.shutdownNow();
        }
        Reply retried = exportWithProof(account, "midroot-fresh");
        assertEquals(200, retried.status, retried.body);
        assertEquals(40, retried.node().path("workouts").get(0).path("exercises").size());
    }

    @Test
    void disableBeforeFinalClosureAbortsWithoutPartialSuccess() throws Exception {
        BoundAccount account = provisionTracked("closure-subject", "closure-signup");
        seedWideWorkout(account, "Closure", 10, 500);
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        String challengeId = proof.node().path("challengeId").asText();
        lenient().when(verifier.verify("closure-token", proof.node().path("nonce").asText()))
                .thenReturn(new Verified("closure-subject", null));
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"closure-token\"}";
        GateControl control = GateControl.watch(GatePoint.BEFORE_FINAL_CLOSURE);
        exportHooks.control.set(control);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DripRead> pending = pool.submit(() -> dripRead(body));
            control.awaitEntry(GatePoint.BEFORE_FINAL_CLOSURE);
            jdbc.update("UPDATE accounts SET deleted_at=clock_timestamp() WHERE id=?", account.accountId);
            control.release();
            assertAbortedWithoutPartialSuccess(pending.get(180, TimeUnit.SECONDS), challengeId);
        } finally {
            jdbc.update("UPDATE accounts SET deleted_at=NULL WHERE id=?", account.accountId);
            exportHooks.control.set(GateControl.open());
            control.release();
            pool.shutdownNow();
        }
        assertEquals(200, exportWithProof(account, "closure-fresh").status);
    }

    @Test
    void postConsumptionFaultBeforeFirstWriteFailsClosedPreByte() {
        BoundAccount account = provisionTracked("segment-fault-subject", "segment-fault-signup");
        seedWideWorkout(account, "SegmentFault", 3, 100);
        // Reach consumption, then fail deterministically before any byte is attempted.
        GateControl control = GateControl.watch(GatePoint.AFTER_CONSUMPTION);
        exportHooks.control.set(control);
        Reply proof = post("/v1/auth/google/challenge", "{\"purpose\":\"export\"}");
        String challengeId = proof.node().path("challengeId").asText();
        lenient().when(verifier.verify("segment-fault-token", proof.node().path("nonce").asText()))
                .thenReturn(new Verified("segment-fault-subject", null));
        String body = "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"segment-fault-token\"}";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> pending = pool.submit(() -> post("/v1/account/export", body));
            control.awaitEntry(GatePoint.AFTER_CONSUMPTION);
            control.failWith(new IllegalStateException("task9a-test-segment-fault"));
            control.release();
            Reply reply;
            try {
                reply = pending.get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("faulted export must terminate with a sanitized pre-byte error", e);
            }
            assertEquals(500, reply.status, reply.body);
            assertEquals("internal_error", reply.node().path("errorCode").asText());
            assertFalse(reply.body.contains("customExercises"), "faulted export must release no export data");
            assertFalse(reply.body.contains("task9a-test-segment-fault"));
            assertTrue(challengeConsumed(challengeId));
        } finally {
            exportHooks.control.set(GateControl.open());
            control.release();
            pool.shutdownNow();
        }
        Reply reuse = post("/v1/account/export",
                "{\"accountId\":\"" + account.accountId + "\",\"challengeId\":\"" + challengeId + "\",\"idToken\":\"segment-fault-token\"}");
        assertEquals(401, reuse.status, reuse.body);
        assertEquals(200, exportWithProof(account, "segment-fault-fresh").status);
    }
}
