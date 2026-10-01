package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** Authenticates and materializes one complete resource within a consistent read-only snapshot. */
@Service
public class ResourceReadService {
    private final SessionAuthorization authorization;
    private final CanonicalAggregateRepository aggregates;
    private final CanonicalResourceMapper mapper = new CanonicalResourceMapper();
    private final TransactionTemplate readTransaction;

    public ResourceReadService(SessionAuthorization authorization, CanonicalAggregateRepository aggregates,
                               PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.aggregates = aggregates;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ReadResult exercise(String bearerToken, UUID id) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearerToken);
            var exercise = aggregates.readVisibleExercise(owner, id);
            return exercise == null ? notFound() : found(mapper.exercise(exercise));
        });
    }

    public ReadResult workout(String bearerToken, UUID id) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearerToken);
            var workout = aggregates.readWorkout(owner, id);
            return workout == null ? notFound() : found(mapper.workout(workout));
        });
    }

    public ReadResult routine(String bearerToken, UUID id) {
        return readTransaction.execute(status -> {
            UUID owner = authorization.requireLiveAccessForRead(bearerToken);
            var routine = aggregates.readRoutine(owner, id);
            return routine == null ? notFound() : found(mapper.routine(routine));
        });
    }

    public sealed interface ReadResult permits Found, NotFound {
    }

    public record Found(Map<String, Object> resource) implements ReadResult {
    }

    public record NotFound() implements ReadResult {
    }

    private static ReadResult found(Map<String, Object> resource) {
        return new Found(resource);
    }

    private static ReadResult notFound() {
        return new NotFound();
    }
}
