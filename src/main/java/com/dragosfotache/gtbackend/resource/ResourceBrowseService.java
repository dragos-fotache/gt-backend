package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import com.dragosfotache.gtbackend.persistence.ResourceBrowseRepository;
import com.dragosfotache.gtbackend.resource.BrowseQueries.ExerciseFilters;
import com.dragosfotache.gtbackend.resource.BrowseQueries.PageOptions;
import com.dragosfotache.gtbackend.resource.ListPageTokenService.Anchor;
import com.dragosfotache.gtbackend.resource.ListPageTokenService.Endpoint;
import com.dragosfotache.gtbackend.resource.ListPageTokenService.UuidAnchor;
import com.dragosfotache.gtbackend.resource.ListPageTokenService.WorkoutAnchor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live, account-authorized browsing pages; every page is one consistent read snapshot. */
@Service
public class ResourceBrowseService {
    private static final int EXERCISE_SCAN_CHUNK = 256;
    private static final String CATALOGUE_VERSION = "gymtracka-catalogue-v1";

    private final SessionAuthorization authorization;
    private final CanonicalAggregateRepository aggregates;
    private final ResourceBrowseRepository browse;
    private final ListPageTokenService tokens;
    private final CanonicalResourceMapper mapper = new CanonicalResourceMapper();
    private final TransactionTemplate readTransaction;

    public ResourceBrowseService(SessionAuthorization authorization, CanonicalAggregateRepository aggregates,
                                 ResourceBrowseRepository browse, ListPageTokenService tokens,
                                 PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.aggregates = aggregates;
        this.browse = browse;
        this.tokens = tokens;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public BrowsePage exercises(String bearer, ExerciseFilters filters, PageOptions options) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearer);
            byte[] context = tokens.exerciseContext(filters.search(), filters.muscleGroupIds(), filters.equipmentIds());
            PageState<UuidAnchor> page = continuation(options.pageToken(), owner, Endpoint.EXERCISES, context,
                    options.limit(), UuidAnchor.class);
            UUID afterId = page.anchor() == null ? null : page.anchor().id();
            Instant expiry = page.expiresAt();
            List<UUID> matchingIds = matchingExerciseIds(owner, afterId, filters, options.limit() + 1);
            boolean hasMore = matchingIds.size() > options.limit();
            List<Map<String, Object>> items = matchingIds.stream().limit(options.limit())
                    .map(id -> aggregates.readVisibleExercise(owner, id))
                    .map(value -> {
                        if (value == null) throw new IllegalStateException("visible exercise disappeared in read snapshot");
                        return mapper.exercise(value);
                    }).toList();
            String next = hasMore ? tokens.issue(owner, Endpoint.EXERCISES, context, options.limit(),
                    new UuidAnchor(matchingIds.get(options.limit() - 1)), expiry) : null;
            return new BrowsePage(items, hasMore, next, CATALOGUE_VERSION);
        });
    }

    public BrowsePage workouts(String bearer, String normalizedStatus, PageOptions options) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearer);
            byte[] context = tokens.workoutContext(normalizedStatus);
            PageState<WorkoutAnchor> page = continuation(options.pageToken(), owner, Endpoint.WORKOUTS, context,
                    options.limit(), WorkoutAnchor.class);
            WorkoutAnchor after = page.anchor();
            Instant expiry = page.expiresAt();
            List<ResourceBrowseRepository.WorkoutSummary> rows = browse.workouts(owner, normalizedStatus, after,
                    options.limit() + 1);
            boolean hasMore = rows.size() > options.limit();
            List<Map<String, Object>> items = rows.stream().limit(options.limit()).map(mapper::workoutSummary).toList();
            String next = hasMore ? tokens.issue(owner, Endpoint.WORKOUTS, context, options.limit(),
                    workoutAnchor(rows.get(options.limit() - 1)), expiry) : null;
            return new BrowsePage(items, hasMore, next, null);
        });
    }

    public BrowsePage routines(String bearer, PageOptions options) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearer);
            byte[] context = tokens.routineContext();
            PageState<UuidAnchor> page = continuation(options.pageToken(), owner, Endpoint.ROUTINES, context,
                    options.limit(), UuidAnchor.class);
            UUID afterId = page.anchor() == null ? null : page.anchor().id();
            Instant expiry = page.expiresAt();
            List<UUID> ids = browse.savedRoutineIds(owner, afterId, options.limit() + 1);
            boolean hasMore = ids.size() > options.limit();
            List<Map<String, Object>> items = ids.stream().limit(options.limit()).map(id -> {
                var routine = aggregates.readRoutine(owner, id);
                if (routine == null) throw new IllegalStateException("saved routine disappeared in read snapshot");
                return mapper.routine(routine);
            }).toList();
            String next = hasMore ? tokens.issue(owner, Endpoint.ROUTINES, context, options.limit(),
                    new UuidAnchor(ids.get(options.limit() - 1)), expiry) : null;
            return new BrowsePage(items, hasMore, next, null);
        });
    }

    private List<UUID> matchingExerciseIds(UUID owner, UUID afterId, ExerciseFilters filters, int wanted) {
        List<UUID> matching = new ArrayList<>(wanted);
        UUID scanAfter = afterId;
        while (matching.size() < wanted) {
            List<ResourceBrowseRepository.ExerciseCandidate> candidates = browse.exerciseCandidates(owner, scanAfter,
                    filters, EXERCISE_SCAN_CHUNK);
            if (candidates.isEmpty()) break;
            for (ResourceBrowseRepository.ExerciseCandidate candidate : candidates) {
                scanAfter = candidate.id();
                if (candidate.matches(filters.search())) matching.add(candidate.id());
                if (matching.size() == wanted) break;
            }
            if (candidates.size() < EXERCISE_SCAN_CHUNK || matching.size() == wanted) break;
        }
        return matching;
    }

    private <T extends Anchor> PageState<T> continuation(String pageToken, UUID owner, Endpoint endpoint,
                                                          byte[] context, int limit, Class<T> anchorType) {
        if (pageToken == null) return new PageState<>(null, tokens.firstPageExpiry());
        ListPageTokenService.Continuation continuation = tokens.validate(pageToken, owner, endpoint, context, limit);
        if (!anchorType.isInstance(continuation.anchor()))
            throw new ListPageTokenService.InvalidPageTokenException();
        return new PageState<>(anchorType.cast(continuation.anchor()), continuation.expiresAt());
    }

    private static WorkoutAnchor workoutAnchor(ResourceBrowseRepository.WorkoutSummary summary) {
        return new WorkoutAnchor(summary.startedAt(), summary.id());
    }

    private record PageState<T extends Anchor>(T anchor, Instant expiresAt) {
    }

    public record BrowsePage(List<Map<String, Object>> items, boolean hasMore, String nextPageToken,
                             String catalogueVersion) {
        public BrowsePage {
            items = List.copyOf(items);
        }
    }
}
