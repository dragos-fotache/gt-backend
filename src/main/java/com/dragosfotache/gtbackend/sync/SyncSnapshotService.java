package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthService;
import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository.FrozenRecord;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository.RootType;
import com.dragosfotache.gtbackend.resource.CanonicalResourceMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures immutable account snapshots and serves deterministic, durable record pages. */
@Service
public class SyncSnapshotService {
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;
    private static final int ROOT_ID_CHUNK = 100;
    private static final int TRANSACTION_ATTEMPTS = 4;
    private static final long SNAPSHOT_LIFETIME_SECONDS = 86_400;
    private final JdbcTemplate jdbc;
    private final SessionAuthorization authorization;
    private final SyncCursorRegistry cursors;
    private final CanonicalAggregateRepository aggregates;
    private final CanonicalResourceMapper mapper = new CanonicalResourceMapper();
    private final SyncSnapshotRepository snapshots;
    private final SnapshotPageTokenService tokens;
    private final Clock clock;
    private final TransactionTemplate snapshotTransaction;

    public SyncSnapshotService(JdbcTemplate jdbc, SessionAuthorization authorization, SyncCursorRegistry cursors,
                               CanonicalAggregateRepository aggregates, SyncSnapshotRepository snapshots,
                               SnapshotPageTokenService tokens, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.cursors = cursors;
        this.aggregates = aggregates;
        this.snapshots = snapshots;
        this.tokens = tokens;
        this.clock = clock;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Map<String, Object> start(String bearerToken, int limit) {
        for (int attempt = 0; attempt < TRANSACTION_ATTEMPTS; attempt++) {
            try {
                PageResult result = snapshotTransaction.execute(status -> capture(bearerToken, limit));
                tokens.requireNotExpired(result.expiresAt());
                return result.body();
            } catch (SyncCursorRegistry.ConcurrentRegistration conflict) {
                // Another pull/snapshot registered this boundary outside our RR view.
            } catch (RuntimeException failure) {
                if (!isSerializationConflict(failure)) throw failure;
                // Retry only the narrow registry/serialization race in a fresh transaction.
            }
        }
        throw new TemporarilyUnavailable();
    }

    public Map<String, Object> continueSnapshot(String bearerToken, String pageToken, int limit) {
        PageResult result = snapshotTransaction.execute(status -> {
            UUID accountId = authorization.requireLiveAccessForRead(bearerToken);
            SnapshotPageTokenService.Claims claims = tokens.validate(pageToken, accountId, limit);
            SyncSnapshotRepository.Header header = snapshots.findHeader(accountId, claims.snapshotId());
            if (header == null) throw new SyncSnapshotRepository.CorruptSnapshot();
            validateHeader(header, claims);
            if (claims.offset() >= header.recordCount()) throw new SnapshotPageTokenService.InvalidSnapshotToken();
            List<FrozenRecord> page = snapshots.readPage(accountId, claims.snapshotId(), claims.offset(), limit,
                    header.recordCount());
            return pageResult(page, header, accountId, claims.snapshotId(), claims.offset(), limit);
        });
        tokens.requireNotExpired(result.expiresAt());
        return result.body();
    }

    private PageResult capture(String bearerToken, int limit) {
        UUID accountId = authorization.requireLiveAccessForRead(bearerToken);
        SyncBoundary boundary = jdbc.query("select committed_cursor,pruned_through from account_sync_state where account_id=?",
                rs -> rs.next() ? new SyncBoundary(rs.getLong(1), rs.getLong(2)) : null, accountId);
        if (boundary == null || boundary.committed() < 0 || boundary.prunedThrough() < 0
                || boundary.prunedThrough() > boundary.committed())
            throw new SyncSnapshotRepository.CorruptSnapshot();

        String baselineCursor = cursors.issue(accountId, boundary.committed());
        // The auth and sync-state reads above establish the RR view. Sample creation exactly once
        // before reading roots; PostgreSQL stores timestamps at microsecond precision.
        Instant createdAt = microsecondPrecision(clock.instant());
        Instant expiresAt = createdAt.plusSeconds(SNAPSHOT_LIFETIME_SECONDS);
        UUID snapshotId = UUID.randomUUID();
        snapshots.create(accountId, snapshotId, baselineCursor, createdAt, expiresAt);
        long recordCount = captureRoots(accountId, snapshotId);
        snapshots.complete(accountId, snapshotId, recordCount);
        SyncSnapshotRepository.Header header = new SyncSnapshotRepository.Header(baselineCursor, createdAt,
                expiresAt, recordCount);
        List<FrozenRecord> firstPage = snapshots.readPage(accountId, snapshotId, 0, limit, recordCount);
        PageResult result = pageResult(firstPage, header, accountId, snapshotId, 0, limit);
        // Throwing here rolls back frozen payloads and any newly registered baseline cursor.
        tokens.requireNotExpired(expiresAt);
        return result;
    }

    private long captureRoots(UUID accountId, UUID snapshotId) {
        long ordinal = 0;
        for (RootType type : RootType.values()) {
            UUID afterId = null;
            while (true) {
                List<UUID> ids = snapshots.nextRootIds(accountId, type, afterId, ROOT_ID_CHUNK);
                if (ids.isEmpty()) break;
                for (UUID id : ids) {
                    Map<String, Object> canonical = canonicalRecord(accountId, type, id);
                    Map<String, Object> change = upsert(type, id, canonical);
                    snapshots.insertRecord(accountId, snapshotId, ordinal++, type.wire(), id, change);
                    afterId = id;
                }
            }
        }
        return ordinal;
    }

    private Map<String, Object> canonicalRecord(UUID accountId, RootType type, UUID id) {
        return switch (type) {
            case CUSTOM_EXERCISE -> {
                var value = aggregates.readVisibleExercise(accountId, id);
                if (value == null || !value.userCreated() || value.revision() == null)
                    throw new SyncSnapshotRepository.CorruptSnapshot();
                yield mapper.exercise(value);
            }
            case ROUTINE -> {
                var value = aggregates.readRoutine(accountId, id);
                if (value == null) throw new SyncSnapshotRepository.CorruptSnapshot();
                yield mapper.routine(value);
            }
            case WORKOUT -> {
                var value = aggregates.readWorkout(accountId, id);
                if (value == null) throw new SyncSnapshotRepository.CorruptSnapshot();
                yield mapper.workout(value);
            }
        };
    }

    private Map<String, Object> upsert(RootType type, UUID id, Map<String, Object> record) {
        Object revisionValue = record.get("revision");
        if (!(revisionValue instanceof Number revision) || revision.longValue() < 1)
            throw new SyncSnapshotRepository.CorruptSnapshot();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("aggregateType", type.wire());
        change.put("aggregateId", id.toString());
        change.put("operation", "upsert");
        change.put("revision", revision.longValue());
        change.put("record", record);
        return change;
    }

    private PageResult pageResult(List<FrozenRecord> page, SyncSnapshotRepository.Header header,
                                  UUID accountId, UUID snapshotId, long offset, int limit) {
        long nextOffset = offset + page.size();
        boolean hasMore = nextOffset < header.recordCount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.stream().map(FrozenRecord::value).toList());
        result.put("baselineCursor", header.baselineCursor());
        result.put("hasMore", hasMore);
        result.put("nextPageToken", hasMore
                ? tokens.issue(accountId, snapshotId, nextOffset, limit, header.expiresAt()) : null);
        return new PageResult(result, header.expiresAt());
    }

    private void validateHeader(SyncSnapshotRepository.Header header, SnapshotPageTokenService.Claims claims) {
        if (header.recordCount() < 0 || header.expiresAt() == null || header.createdAt() == null
                || !header.expiresAt().equals(header.createdAt().plusSeconds(SNAPSHOT_LIFETIME_SECONDS))
                || !header.expiresAt().equals(claims.expiresAt())
                || header.baselineCursor() == null || header.baselineCursor().isEmpty())
            throw new SyncSnapshotRepository.CorruptSnapshot();
    }

    private static Instant microsecondPrecision(Instant value) {
        return Instant.ofEpochSecond(value.getEpochSecond(), (value.getNano() / 1_000) * 1_000L);
    }

    private boolean isSerializationConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && "40001".equals(sql.getSQLState())) return true;
        return false;
    }

    private record SyncBoundary(long committed, long prunedThrough) { }
    private record PageResult(Map<String, Object> body, Instant expiresAt) { }

    public static final class TemporarilyUnavailable extends RuntimeException { }
}
