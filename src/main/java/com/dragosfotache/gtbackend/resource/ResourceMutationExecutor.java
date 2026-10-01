package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.persistence.AccountWriteRepository;
import com.dragosfotache.gtbackend.sync.RoutineUpsertExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Owns resource-key replay and HTTP response persistence around shared domain execution.
 */
@Service
public class ResourceMutationExecutor {
    private final JdbcTemplate jdbc;
    private final SessionAuthorization authorization;
    private final AccountWriteRepository accountWrites;
    private final RoutineUpsertExecutor commands;
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate transactions;

    public ResourceMutationExecutor(JdbcTemplate jdbc, SessionAuthorization authorization,
                                    AccountWriteRepository accountWrites, RoutineUpsertExecutor commands,
                                    PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.accountWrites = accountWrites;
        this.commands = commands;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Result execute(String token, ResourcePreflight.Prepared request, UUID key) {
        return transactions.execute(status -> {
            UUID owner = authorization.requireLiveAccess(token);
            return accountWrites.inAccountWrite(owner, session -> executeInAccount(owner, request, key));
        });
    }

    private Result executeInAccount(UUID owner, ResourcePreflight.Prepared request, UUID key) {
        ResourceReplay replay = findReplay(owner, key);
        if (replay != null) {
            if (!sameRequest(replay, request)) return response(409, Map.of("errorCode", "idempotency_key_reused"));
            return new Result(replay.status, replay.responseBody);
        }

        RoutineUpsertExecutor.DomainCommandResult commandResult = routePolicyRejection(owner, request)
                .<RoutineUpsertExecutor.DomainCommandResult>map(RoutineUpsertExecutor.DomainCommandResult.Rejected::new)
                .orElseGet(() -> commands.executeDomainCommandInAccountWrite(owner, request.mutation()));
        Result result = responseFor(request, commandResult);
        persistReplay(owner, key, request, result);
        return result;
    }

    private ResourceReplay findReplay(UUID owner, UUID key) {
        return jdbc.query("select request_method,request_route,request_body,response_status,response_body from resource_mutations where owner_id=? and idempotency_key=? for update",
                rs -> rs.next() ? new ResourceReplay(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getInt(4), rs.getBytes(5)) : null,
                owner, key);
    }

    private boolean sameRequest(ResourceReplay replay, ResourcePreflight.Prepared request) {
        return replay.method.equals(request.method()) && replay.route.equals(request.route())
                && Arrays.equals(replay.body, request.rawBody());
    }

    private Result responseFor(ResourcePreflight.Prepared request, RoutineUpsertExecutor.DomainCommandResult commandResult) {
        if (commandResult instanceof RoutineUpsertExecutor.DomainCommandResult.Rejected rejected) {
            return response(domainStatus(rejected.errorCode()), Map.of("errorCode", rejected.errorCode()));
        }
        if (request.responseKind() == ResourcePreflight.ResponseKind.NO_CONTENT) return new Result(204, new byte[0]);

        var accepted = (RoutineUpsertExecutor.DomainCommandResult.Accepted) commandResult;
        Object canonicalRoot = canonicalRoot(accepted.batch(), request.aggregateId());
        return response(request.responseKind().status(), canonicalRoot);
    }

    private Object canonicalRoot(Map<String, Object> batch, String aggregateId) {
        List<?> changes = (List<?>) batch.get("changes");
        for (Object item : changes) {
            Map<?, ?> change = (Map<?, ?>) item;
            if (aggregateId.equals(change.get("aggregateId")) && "upsert".equals(change.get("operation")))
                return change.get("record");
        }
        throw new IllegalStateException("resource command produced no canonical root");
    }

    private Result response(int status, Object body) {
        return new Result(status, responseBytes(body));
    }

    private byte[] responseBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int domainStatus(String code) {
        return switch (code) {
            case "read_only" -> 403;
            case "not_found" -> 404;
            case "idempotency_key_reused", "id_collision", "invalid_state", "source_shape_changed",
                 "history_shape_changed", "exercise_in_use" -> 409;
            case "validation_error", "invalid_reference" -> 422;
            default -> 500;
        };
    }

    private void persistReplay(UUID owner, UUID key, ResourcePreflight.Prepared request, Result result) {
        jdbc.update("insert into resource_mutations(owner_id,idempotency_key,request_method,request_route,request_body,response_status,response_body) values(?,?,?,?,?,?,?)",
                owner, key, request.method(), request.route(), request.rawBody(), result.status(), result.body());
    }

    private Optional<String> routePolicyRejection(UUID owner, ResourcePreflight.Prepared request) {
        UUID target = UUID.fromString(request.aggregateId());
        String route = request.route();
        if (request.method().equals("POST") && route.equals("/v1/exercises")) {
            return Optional.ofNullable(jdbc.query("select 1 from exercises where id=? union all select 1 from root_id_reservations where aggregate_type='customExercise' and aggregate_id=? limit 1",
                    rs -> rs.next() ? "id_collision" : null, target, target));
        }
        if (request.method().equals("PUT") && route.startsWith("/v1/exercises/")) {
            if (jdbc.query("select 1 from exercises where id=? and owner_id is null", rs -> rs.next() ? 1 : null, target) != null)
                return Optional.of("read_only");
            return Optional.ofNullable(jdbc.query("select 1 from exercises where id=? and owner_id=? and is_user_created=true and deleted_at is null",
                    rs -> rs.next() ? null : "not_found", target, owner));
        }
        if (request.method().equals("POST") && route.equals("/v1/routines")) {
            return Optional.ofNullable(jdbc.query("select 1 from root_id_reservations where aggregate_type='routine' and aggregate_id=? union all select 1 from child_id_reservations where family='routine' and child_id=? limit 1",
                    rs -> rs.next() ? "id_collision" : null, target, target));
        }
        if (request.method().equals("PUT") && route.startsWith("/v1/routines/")) {
            return Optional.ofNullable(jdbc.query("select 1 from routines where id=? and owner_id=? and deleted_at is null",
                    rs -> rs.next() ? null : "not_found", target, owner));
        }
        return Optional.empty();
    }

    public record Result(int status, byte[] body) {
        public Result {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private record ResourceReplay(String method, String route, byte[] body, int status, byte[] responseBody) {
    }
}
