package com.dragosfotache.gtbackend.sync;

import com.dragosfotache.gtbackend.auth.AuthService;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/** HTTP query/body policy and safe status mapping for frozen sync snapshots. */
@RestController
public class SyncSnapshotController {
    private static final Set<String> ALLOWED_QUERY = Set.of("pageToken", "limit");
    private final SyncSnapshotService snapshots;

    public SyncSnapshotController(SyncSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping(value = "/v1/sync/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> snapshot(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody(required = false) byte[] body,
                                      HttpServletRequest request) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7)
            return error(401, "authentication_required");
        if (body != null && body.length != 0) return error(422, "validation_error");

        ParsedQuery query;
        try {
            query = parseQuery(request);
        } catch (InvalidRequest e) {
            return error(400, "invalid_request");
        } catch (RuntimeException e) {
            return serviceError(e);
        }

        try {
            Map<String, Object> result = query.pageToken() == null
                    ? snapshots.start(authorization.substring(7), query.limit())
                    : snapshots.continueSnapshot(authorization.substring(7), query.pageToken(), query.limit());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
        } catch (AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (SnapshotPageTokenService.InvalidSnapshotToken e) {
            return error(400, "invalid_snapshot_token");
        } catch (SnapshotPageTokenService.SnapshotExpired e) {
            return error(410, "snapshot_expired");
        } catch (SyncSnapshotService.TemporarilyUnavailable e) {
            return error(503, "temporarily_unavailable");
        } catch (RuntimeException e) {
            return serviceError(e);
        }
    }

    private ParsedQuery parseQuery(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        for (String name : parameters.keySet())
            if (!ALLOWED_QUERY.contains(name)) throw new InvalidRequest();
        String pageToken = scalar(parameters, "pageToken");
        String rawLimit = scalar(parameters, "limit");
        int limit = SyncSnapshotService.DEFAULT_LIMIT;
        if (rawLimit != null) {
            if (rawLimit.isEmpty()) throw new InvalidRequest();
            for (int i = 0; i < rawLimit.length(); i++)
                if (rawLimit.charAt(i) < '0' || rawLimit.charAt(i) > '9') throw new InvalidRequest();
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException e) {
                throw new InvalidRequest();
            }
            if (limit < 1 || limit > SyncSnapshotService.MAX_LIMIT) throw new InvalidRequest();
        }
        return new ParsedQuery(pageToken, limit);
    }

    private String scalar(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequest();
        return values[0];
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("errorCode", code));
    }

    private static ResponseEntity<Map<String, Object>> serviceError(Throwable failure) {
        boolean transientFailure = isTransient(failure);
        return error(transientFailure ? 503 : 500,
                transientFailure ? "temporarily_unavailable" : "internal_error");
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTransientException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("40"))) return true;
            if (cause instanceof org.springframework.dao.TransientDataAccessException) return true;
        }
        return false;
    }

    private record ParsedQuery(String pageToken, int limit) { }
    private static final class InvalidRequest extends RuntimeException { }
}
