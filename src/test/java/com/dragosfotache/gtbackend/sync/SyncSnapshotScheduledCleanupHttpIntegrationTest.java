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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncSnapshotScheduledCleanupHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AUDIENCE = "snapshot-scheduled-cleanup-audience";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.google-audience", () -> AUDIENCE);
        registry.add("auth.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("sync.snapshot.cleanup.enabled", () -> "true");
        registry.add("sync.snapshot.cleanup.initial-delay-ms", () -> "100");
        registry.add("sync.snapshot.cleanup.fixed-delay-ms", () -> "100");
        registry.add("sync.history.cleanup.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthCrypto crypto;
    @MockitoSpyBean Clock clock;

    private record Account(UUID id, String token) { }

    @AfterEach
    void restoreClockAndRemoveTestSnapshots() {
        reset(clock);
        jdbc.update("delete from sync_snapshots");
    }

    private String idempotencyKey() { return UUID.randomUUID().toString(); }

    private Account account() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into accounts(id) values(?)", accountId);
        jdbc.update("insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())", sessionId, accountId);
        jdbc.update("insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at) " +
                        "values(?,?,'access',clock_timestamp(),clock_timestamp()+interval '2 hours')",
                crypto.digest(token), sessionId);
        return new Account(accountId, token);
    }

    private HttpResponse<byte[]> request(Account account, String method, String path, String body) throws Exception {
        return request(account, method, path, body, null);
    }

    private HttpResponse<byte[]> request(Account account, String method, String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + account.token());
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        if (body != null) builder.header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return HttpClient.newHttpClient().send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void springScheduledTickPhysicallyDeletesExpiredFrozenPayloadWithoutManualRunnerInvocation() throws Exception {
        Account owner = account();
        String customId = UUID.randomUUID().toString();
        HttpResponse<byte[]> custom = request(owner, "POST", "/v1/exercises",
                "{\"id\":\"" + customId + "\",\"name\":\"scheduled payload\",\"category\":\"snapshot category\"," +
                        "\"difficultyLevel\":\"advanced\",\"forceType\":\"pull\",\"mechanics\":\"compound\"," +
                        "\"primaryMuscleGroupId\":\"5181c3d4-3cfd-4e9e-9ae3-3de472e084bc\"," +
                        "\"secondaryMuscleGroupIds\":[\"cf81b51f-97d4-4cb2-ab3c-1d59472481ed\"]," +
                        "\"equipmentId\":\"22ad09f6-c689-4096-ad05-971ca8a6f0aa\",\"equipmentNumItems\":0,\"instructions\":[]}", idempotencyKey());
        assertEquals(201, custom.statusCode(), new String(custom.body(), StandardCharsets.UTF_8));

        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        doReturn(createdAt).when(clock).instant();
        HttpResponse<byte[]> captured = request(owner, "GET", "/v1/sync/snapshot?limit=10", null);
        assertEquals(200, captured.statusCode(), new String(captured.body(), StandardCharsets.UTF_8));
        JsonNode body = JSON.readTree(captured.body());
        assertEquals(customId, body.path("records").get(0).path("aggregateId").asText());
        UUID snapshotId = jdbc.queryForObject("select snapshot_id from sync_snapshots where account_id=?",
                UUID.class, owner.id());
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                Integer.class, owner.id(), snapshotId));

        doReturn(createdAt.plusSeconds(86_400)).when(clock).instant();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int headers;
        int records;
        do {
            headers = jdbc.queryForObject("select count(*) from sync_snapshots where account_id=? and snapshot_id=?",
                    Integer.class, owner.id(), snapshotId);
            records = jdbc.queryForObject("select count(*) from sync_snapshot_records where account_id=? and snapshot_id=?",
                    Integer.class, owner.id(), snapshotId);
            if (headers == 0 && records == 0) break;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertEquals(0, headers, "the actual @Scheduled tick eventually removes the expired header");
        assertEquals(0, records, "the scheduled database cascade physically removes the frozen domain payload");
    }
}
