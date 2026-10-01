package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.auth.AuthService;
import com.dragosfotache.gtbackend.auth.SessionAuthorization;
import com.dragosfotache.gtbackend.sync.PushPreflight;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ResourceController {
    private final SessionAuthorization authorization;
    private final ResourcePreflight preflight;
    private final ResourceMutationExecutor executor;

    public ResourceController(SessionAuthorization authorization, ResourcePreflight preflight, ResourceMutationExecutor executor) {
        this.authorization = authorization;
        this.preflight = preflight;
        this.executor = executor;
    }

    @PostMapping(value = "/v1/exercises", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createExercise(@RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/exercises", auth, key, body, request);
    }

    @PutMapping(value = "/v1/exercises/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> updateExercise(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("PUT", "/v1/exercises/" + id, auth, key, body, request);
    }

    @DeleteMapping("/v1/exercises/{id}")
    public ResponseEntity<byte[]> deleteExercise(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("DELETE", "/v1/exercises/" + id, auth, key, body, request);
    }

    @PostMapping(value = "/v1/routines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createRoutine(@RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/routines", auth, key, body, request);
    }

    @PutMapping(value = "/v1/routines/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> updateRoutine(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("PUT", "/v1/routines/" + id, auth, key, body, request);
    }

    @DeleteMapping("/v1/routines/{id}")
    public ResponseEntity<byte[]> deleteRoutine(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("DELETE", "/v1/routines/" + id, auth, key, body, request);
    }

    @PostMapping(value = "/v1/routines/{id}/save", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> saveRoutine(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/routines/" + id + "/save", auth, key, body, request);
    }

    @PostMapping(value = "/v1/routines/{id}/start-workout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> startRoutineWorkout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/routines/" + id + "/start-workout", auth, key, body, request);
    }

    @PostMapping(value = "/v1/workouts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> startWorkout(@RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/workouts", auth, key, body, request);
    }

    @PutMapping(value = "/v1/workouts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> updateWorkout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("PUT", "/v1/workouts/" + id, auth, key, body, request);
    }

    @DeleteMapping("/v1/workouts/{id}")
    public ResponseEntity<byte[]> deleteWorkout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("DELETE", "/v1/workouts/" + id, auth, key, body, request);
    }

    @PostMapping(value = "/v1/workouts/{id}/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> finishWorkout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/workouts/" + id + "/finish", auth, key, body, request);
    }

    @PostMapping(value = "/v1/workouts/{id}/perform-again", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> performAgain(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/workouts/" + id + "/perform-again", auth, key, body, request);
    }

    @PostMapping(value = "/v1/workouts/{id}/save-as-routine", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> saveAsRoutine(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return execute("POST", "/v1/workouts/" + id + "/save-as-routine", auth, key, body, request);
    }

    private ResponseEntity<byte[]> execute(String method, String route, String auth, String key, byte[] body, HttpServletRequest servlet) {
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() == 7)
            return error(401, "authentication_required");
        try {
            authorization.requireLiveAccess(auth.substring(7));
        } catch (AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (RuntimeException e) {
            return error(isTransient(e) ? 503 : 500, isTransient(e) ? "temporarily_unavailable" : "internal_error");
        }
        if (key == null) return error(400, "invalid_request");
        if (!ResourcePreflight.isCanonicalUuidText(key)) return error(422, "validation_error");
        if (servlet.getQueryString() != null) return error(400, "invalid_request");
        final ResourcePreflight.Prepared prepared;
        try {
            prepared = preflight.prepare(method, route, body == null ? new byte[0] : body);
        } catch (PushPreflight.Failure e) {
            return error(e.status, e.code);
        }
        try {
            ResourceMutationExecutor.Result result = executor.execute(auth.substring(7), prepared, UUID.fromString(key));
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status());
            if (result.status() != 204) builder.contentType(MediaType.APPLICATION_JSON);
            return builder.body(result.body());
        } catch (AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (RuntimeException e) {
            return error(isTransient(e) ? 503 : 500, isTransient(e) ? "temporarily_unavailable" : "internal_error");
        }
    }

    private static ResponseEntity<byte[]> error(int status, String code) {
        byte[] body = ("{\"errorCode\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientException || t instanceof org.springframework.dao.TransientDataAccessException)
                return true;
            if (t instanceof java.sql.SQLException s && s.getSQLState() != null
                    && (s.getSQLState().startsWith("08") || s.getSQLState().startsWith("40"))) return true;
        }
        return false;
    }
}
