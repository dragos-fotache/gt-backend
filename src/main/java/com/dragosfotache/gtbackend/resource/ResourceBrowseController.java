package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.resource.BrowseQueries.ExerciseFilters;
import com.dragosfotache.gtbackend.resource.BrowseQueries.PageOptions;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** HTTP query validation and safe response mapping for live browsing endpoints. */
@RestController
public class ResourceBrowseController {
    private static final Set<String> EXERCISE_PARAMETERS = Set.of("q", "muscleGroupId", "equipmentId", "limit", "pageToken");
    private static final Set<String> WORKOUT_PARAMETERS = Set.of("status", "limit", "pageToken");
    private static final Set<String> ROUTINE_PARAMETERS = Set.of("limit", "pageToken");

    private final ResourceBrowseService browse;
    private final ObjectMapper json;

    public ResourceBrowseController(ResourceBrowseService browse, ObjectMapper json) {
        this.browse = browse;
        this.json = json;
    }

    @GetMapping(value = "/v1/catalogue/exercises", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exercises(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody(required = false) byte[] body,
                                            HttpServletRequest request) {
        return handle(authorization, body, bearer -> {
            Map<String, String[]> parameters = request.getParameterMap();
            requireKnownParameters(parameters, EXERCISE_PARAMETERS);
            String search = scalar(parameters, "q");
            var filters = new ExerciseFilters(search, uuids(parameters, "muscleGroupId"),
                    uuids(parameters, "equipmentId"));
            PageOptions options = pageOptions(parameters);
            return browse.exercises(bearer, filters, options);
        });
    }

    @GetMapping(value = "/v1/workouts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> workouts(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody(required = false) byte[] body,
                                           HttpServletRequest request) {
        return handle(authorization, body, bearer -> {
            Map<String, String[]> parameters = request.getParameterMap();
            requireKnownParameters(parameters, WORKOUT_PARAMETERS);
            String status = scalar(parameters, "status");
            if (status != null && !status.equals("active") && !status.equals("finished"))
                throw new InvalidRequestException();
            return browse.workouts(bearer, status, pageOptions(parameters));
        });
    }

    @GetMapping(value = "/v1/routines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> routines(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody(required = false) byte[] body,
                                           HttpServletRequest request) {
        return handle(authorization, body, bearer -> {
            Map<String, String[]> parameters = request.getParameterMap();
            requireKnownParameters(parameters, ROUTINE_PARAMETERS);
            return browse.routines(bearer, pageOptions(parameters));
        });
    }

    private ResponseEntity<byte[]> handle(String authorization, byte[] body,
                                          Function<String, ResourceBrowseService.BrowsePage> operation) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7)
            return error(401, "authentication_required");
        if (body != null && body.length != 0) return error(422, "validation_error");
        try {
            ResourceBrowseService.BrowsePage page = operation.apply(authorization.substring(7));
            return success(page);
        } catch (ListPageTokenService.InvalidPageTokenException e) {
            return error(400, "invalid_page_token");
        } catch (ListPageTokenService.PageExpiredException e) {
            return error(410, "page_expired");
        } catch (InvalidRequestException e) {
            return error(400, "invalid_request");
        } catch (com.dragosfotache.gtbackend.auth.AuthService.AuthException e) {
            return error(401, "authentication_required");
        } catch (RuntimeException e) {
            return isTransient(e) ? error(503, "temporarily_unavailable") : error(500, "internal_error");
        }
    }

    private ResponseEntity<byte[]> success(ResourceBrowseService.BrowsePage page) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            if (page.catalogueVersion() != null) body.put("catalogueVersion", page.catalogueVersion());
            body.put("items", page.items());
            body.put("hasMore", page.hasMore());
            body.put("nextPageToken", page.nextPageToken());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
                    json.writeValueAsBytes(body));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize browse page", e);
        }
    }

    private static PageOptions pageOptions(Map<String, String[]> parameters) {
        String rawLimit = scalar(parameters, "limit");
        int limit = 100;
        if (rawLimit != null) {
            if (!rawLimit.matches("[0-9]+")) throw new InvalidRequestException();
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException e) {
                throw new InvalidRequestException();
            }
        }
        if (limit < 1 || limit > 200) throw new InvalidRequestException();
        return new PageOptions(limit, scalar(parameters, "pageToken"));
    }

    private static String scalar(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }

    private static java.util.List<UUID> uuids(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null) return java.util.List.of();
        return Arrays.stream(values).map(ResourceBrowseController::canonicalUuid).toList();
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException();
        }
    }

    private static void requireKnownParameters(Map<String, String[]> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet())) throw new InvalidRequestException();
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

    private static final class InvalidRequestException extends RuntimeException {
    }
}
