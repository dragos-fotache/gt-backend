package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthService;
import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/sync")
public class SyncPushController {
    private final SessionAuthorization authorization;
    private final PushPreflight preflight;
    private final RoutineUpsertExecutor executor;

    public SyncPushController(SessionAuthorization authorization, PushPreflight preflight, RoutineUpsertExecutor executor) {
        this.authorization = authorization;
        this.preflight = preflight;
        this.executor = executor;
    }

    @PostMapping(value = "/push", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> push(@RequestHeader(value = "Authorization", required = false) String authorizationHeader, @RequestBody(required = false) byte[] body) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ") || authorizationHeader.length() == 7)
            return error(401, "authentication_required");
        try {
            authorization.requireLiveAccess(authorizationHeader.substring(7));
        } catch (AuthService.AuthException e) {
            return error(401, "authentication_required");
        }
        final List<PushPreflight.Mutation> mutations;
        try {
            mutations = preflight.parse(body == null ? new byte[0] : body);
        } catch (PushPreflight.Failure e) {
            return error(e.status, e.code);
        }
        if (mutations.isEmpty()) return ResponseEntity.ok(Map.of("results", List.of()));
        try {
            return ResponseEntity.ok(Map.of("results", executor.push(authorizationHeader.substring(7), mutations)));
        } catch (RoutineUpsertExecutor.TemporarilyUnavailable e) {
            return error(503, "temporarily_unavailable");
        } catch (AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (org.springframework.dao.DataAccessException e) {
            return error(isTransientDatabaseFailure(e) ? 503 : 500, isTransientDatabaseFailure(e) ? "temporarily_unavailable" : "internal_error");
        } catch (RuntimeException e) {
            return error(500, "internal_error");
        }
    }

    private static boolean isTransientDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTransientException) return true;
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))
                return true;
        }
        return failure instanceof org.springframework.dao.TransientDataAccessException;
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("errorCode", code));
    }
}
