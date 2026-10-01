package com.dragosfotache.gtbackend.resource;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** HTTP validation and safe status mapping for individual resource reads. */
@RestController
public class ResourceReadController {
    private final ResourceReadService reads;
    private final ObjectMapper json;

    public ResourceReadController(ResourceReadService reads, ObjectMapper json) {
        this.reads = reads;
        this.json = json;
    }

    @GetMapping(value = "/v1/exercises/{exerciseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exercise(@PathVariable String exerciseId,
                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody(required = false) byte[] body,
                                           HttpServletRequest request) {
        return read(exerciseId, authorization, body, request, reads::exercise);
    }

    @GetMapping(value = "/v1/workouts/{workoutId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> workout(@PathVariable String workoutId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return read(workoutId, authorization, body, request, reads::workout);
    }

    @GetMapping(value = "/v1/routines/{routineId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> routine(@PathVariable String routineId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return read(routineId, authorization, body, request, reads::routine);
    }

    private ResponseEntity<byte[]> read(String pathId, String authorization, byte[] body,
                                        HttpServletRequest request, ReadOperation operation) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7)
            return error(401, "authentication_required");
        if (request.getQueryString() != null && !request.getQueryString().isEmpty())
            return error(400, "invalid_request");
        if (body != null && body.length != 0) return error(422, "validation_error");
        if (!ResourcePreflight.isCanonicalUuidText(pathId)) return error(422, "validation_error");

        try {
            UUID id = UUID.fromString(pathId);
            ResourceReadService.ReadResult result = operation.get(authorization.substring(7), id);
            if (result instanceof ResourceReadService.NotFound) return error(404, "not_found");
            var found = (ResourceReadService.Found) result;
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json(found.resource()));
        } catch (com.dragosfotache.gtbackend.auth.AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (RuntimeException e) {
            return isTransient(e) ? error(503, "temporarily_unavailable") : error(500, "internal_error");
        }
    }

    @FunctionalInterface
    private interface ReadOperation {
        ResourceReadService.ReadResult get(String bearerToken, UUID id);
    }

    private byte[] json(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<byte[]> error(int status, String code) {
        byte[] body = ("{\"errorCode\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
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
}
