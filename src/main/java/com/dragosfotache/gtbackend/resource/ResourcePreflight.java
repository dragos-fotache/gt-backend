package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.sync.PushPreflight;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class ResourcePreflight {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> CUSTOM_EXERCISE_FIELDS = Set.of("id", "name", "primaryMuscleGroupId", "secondaryMuscleGroupIds", "equipmentId", "equipmentNumItems", "category", "difficultyLevel", "forceType", "mechanics", "instructions");
    private static final JsonFactory FACTORY = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
    private static final ObjectMapper JSON = new ObjectMapper(FACTORY);
    private final PushPreflight pushPreflight;

    public ResourcePreflight(PushPreflight pushPreflight) {
        this.pushPreflight = pushPreflight;
    }

    public Prepared prepare(String method, String route, byte[] rawBody) {
        ResolvedRoute resolved = resolveRoute(route);
        ObjectNode body = parseRouteBody(method, resolved, rawBody);
        validateUtcTimestamps(body);
        CommandSpec command = toCommand(method, resolved, body);
        return validateAndAdapt(method, route, rawBody, command);
    }

    private ResolvedRoute resolveRoute(String route) {
        String[] parts = route.split("/");
        if (parts.length == 3 && parts[1].equals("v1")) {
            if (parts[2].equals("exercises")) return new ResolvedRoute(DirectRoute.EXERCISES, null);
            if (parts[2].equals("routines")) return new ResolvedRoute(DirectRoute.ROUTINES, null);
            if (parts[2].equals("workouts")) return new ResolvedRoute(DirectRoute.WORKOUTS, null);
        }
        if (parts.length == 4 && parts[1].equals("v1")) {
            DirectRoute itemRoute = switch (parts[2]) {
                case "exercises" -> DirectRoute.EXERCISE;
                case "routines" -> DirectRoute.ROUTINE;
                case "workouts" -> DirectRoute.WORKOUT;
                default -> null;
            };
            if (itemRoute != null) return new ResolvedRoute(itemRoute, pathUuid(parts[3]));
        }
        if (parts.length == 5 && parts[1].equals("v1")) {
            DirectRoute commandRoute = switch (parts[2] + "/" + parts[4]) {
                case "routines/save" -> DirectRoute.ROUTINE_SAVE;
                case "routines/start-workout" -> DirectRoute.ROUTINE_START;
                case "workouts/finish" -> DirectRoute.WORKOUT_FINISH;
                case "workouts/perform-again" -> DirectRoute.WORKOUT_AGAIN;
                case "workouts/save-as-routine" -> DirectRoute.WORKOUT_SAVE_ROUTINE;
                default -> null;
            };
            if (commandRoute != null) return new ResolvedRoute(commandRoute, pathUuid(parts[3]));
        }
        throw validation();
    }

    private ObjectNode parseRouteBody(String method, ResolvedRoute route, byte[] rawBody) {
        if (method.equals("DELETE")) {
            if (rawBody.length != 0 || !route.route().isRootResource()) throw validation();
            return JSON.createObjectNode();
        }
        if (rawBody.length == 0) throw validation();
        return parseBody(rawBody);
    }

    private CommandSpec toCommand(String method, ResolvedRoute route, ObjectNode body) {
        if (method.equals("DELETE")) {
            String aggregateType = route.route() == DirectRoute.EXERCISE ? "customExercise" : route.route().aggregateType();
            return new CommandSpec("aggregate.delete", aggregateType, route.pathId(), JSON.createObjectNode(), ResponseKind.NO_CONTENT);
        }
        return switch (route.route()) {
            case EXERCISES -> createExercise(method, body);
            case EXERCISE -> updateExercise(method, route.pathId(), body);
            case ROUTINES -> createRoutine(method, body);
            case ROUTINE -> updateRoutine(method, route.pathId(), body);
            case ROUTINE_SAVE -> saveRoutine(method, route.pathId(), body);
            case ROUTINE_START -> startFromRoutine(method, route.pathId(), body);
            case WORKOUTS -> startWorkout(method, body);
            case WORKOUT -> updateWorkout(method, route.pathId(), body);
            case WORKOUT_FINISH -> finishWorkout(method, route.pathId(), body);
            case WORKOUT_AGAIN -> performAgain(method, route.pathId(), body);
            case WORKOUT_SAVE_ROUTINE -> saveAsRoutine(method, route.pathId(), body);
        };
    }

    private CommandSpec createExercise(String method, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, CUSTOM_EXERCISE_FIELDS);
        String id = textId(body, "id");
        return new CommandSpec("customExercise.upsert", "customExercise", id, body, ResponseKind.CREATED);
    }

    private CommandSpec updateExercise(String method, String pathId, ObjectNode body) {
        requireMethod(method, "PUT");
        allow(body, CUSTOM_EXERCISE_FIELDS);
        return new CommandSpec("customExercise.upsert", "customExercise", pathId, body, ResponseKind.OK);
    }

    private CommandSpec createRoutine(String method, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "id");
        String id = textId(body, "id");
        ObjectNode payload = JSON.createObjectNode();
        payload.put("id", id);
        payload.put("name", "");
        payload.put("isDraft", true);
        payload.set("exercises", JSON.createArrayNode());
        return new CommandSpec("routine.upsert", "routine", id, payload, ResponseKind.CREATED);
    }

    private CommandSpec updateRoutine(String method, String pathId, ObjectNode body) {
        requireMethod(method, "PUT");
        allow(body, "id", "name", "isDraft", "exercises");
        return new CommandSpec("routine.upsert", "routine", pathId, body, ResponseKind.OK);
    }

    private CommandSpec saveRoutine(String method, String pathId, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "name");
        return new CommandSpec("routine.save", "routine", pathId, body, ResponseKind.OK);
    }

    private CommandSpec startFromRoutine(String method, String pathId, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "id", "startedAt", "childIds");
        ObjectNode payload = body.deepCopy();
        payload.put("sourceRoutineId", pathId);
        return new CommandSpec("routine.startWorkout", "workout", textId(body, "id"), payload, ResponseKind.CREATED);
    }

    private CommandSpec startWorkout(String method, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "id", "name", "startedAt");
        return new CommandSpec("workout.start", "workout", textId(body, "id"), body, ResponseKind.CREATED);
    }

    private CommandSpec updateWorkout(String method, String pathId, ObjectNode body) {
        requireMethod(method, "PUT");
        allow(body, "id", "name", "status", "startedAt", "exercises");
        return new CommandSpec("workout.upsert", "workout", pathId, body, ResponseKind.OK);
    }

    private CommandSpec finishWorkout(String method, String pathId, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "finishedAt");
        return new CommandSpec("workout.finish", "workout", pathId, body, ResponseKind.OK);
    }

    private CommandSpec performAgain(String method, String pathId, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "id", "startedAt", "childIds");
        ObjectNode payload = body.deepCopy();
        payload.put("sourceWorkoutId", pathId);
        return new CommandSpec("workout.performAgain", "workout", textId(body, "id"), payload, ResponseKind.CREATED);
    }

    private CommandSpec saveAsRoutine(String method, String pathId, ObjectNode body) {
        requireMethod(method, "POST");
        allow(body, "id", "name", "childIds");
        ObjectNode payload = body.deepCopy();
        payload.put("sourceWorkoutId", pathId);
        return new CommandSpec("workout.saveAsRoutine", "routine", textId(body, "id"), payload, ResponseKind.CREATED);
    }

    private Prepared validateAndAdapt(String method, String route, byte[] rawBody, CommandSpec command) {
        ObjectNode envelope = JSON.createObjectNode().put("mutationId", UUID.randomUUID().toString()).put("type", command.type())
                .put("aggregateType", command.aggregateType()).put("aggregateId", command.aggregateId()).put("localGeneration", 1);
        envelope.set("payload", command.payload());
        ObjectNode outer = JSON.createObjectNode();
        outer.putArray("mutations").add(envelope);
        try {
            PushPreflight.Mutation mutation = pushPreflight.parse(JSON.writeValueAsBytes(outer)).get(0);
            return new Prepared(method, route, rawBody, mutation, command.aggregateType(), command.aggregateId(), command.responseKind());
        } catch (PushPreflight.Failure e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static ObjectNode parseBody(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            try (var parser = FACTORY.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || parser.nextToken() != null) throw invalid();
                if (!(node instanceof ObjectNode object)) throw validation();
                return object;
            }
        } catch (PushPreflight.Failure e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static void validateUtcTimestamps(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ((field.getKey().equals("startedAt") || field.getKey().equals("finishedAt")) && field.getValue().isTextual()) {
                    try {
                        if (OffsetDateTime.parse(field.getValue().textValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).getOffset().getTotalSeconds() != 0)
                            throw validation();
                    } catch (java.time.DateTimeException ignored) {
                        // The shared shape validator reports malformed timestamps.
                    }
                }
                validateUtcTimestamps(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(ResourcePreflight::validateUtcTimestamps);
        }
    }

    private static void allow(ObjectNode node, String... fields) {
        allow(node, Set.of(fields));
    }

    private static void allow(ObjectNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw validation();
        });
    }

    private static void requireMethod(String actual, String required) {
        if (!actual.equals(required)) throw validation();
    }

    static boolean isCanonicalUuidText(String value) {
        return UUID_TEXT.matcher(value).matches();
    }

    private static String pathUuid(String value) {
        if (!isCanonicalUuidText(value)) throw validation();
        return value;
    }

    private static String textId(JsonNode node, String key) {
        JsonNode id = node.get(key);
        if (id == null || !id.isTextual() || !isCanonicalUuidText(id.textValue())) throw validation();
        return id.textValue();
    }

    private static PushPreflight.Failure validation() {
        return new PushPreflight.Failure(422, "validation_error");
    }

    private static PushPreflight.Failure invalid() {
        return new PushPreflight.Failure(400, "invalid_request");
    }

    private enum DirectRoute {
        EXERCISES(null, false), EXERCISE("customExercise", true),
        ROUTINES(null, false), ROUTINE("routine", true), ROUTINE_SAVE("routine", false), ROUTINE_START("routine", false),
        WORKOUTS(null, false), WORKOUT("workout", true), WORKOUT_FINISH("workout", false),
        WORKOUT_AGAIN("workout", false), WORKOUT_SAVE_ROUTINE("workout", false);

        private final String aggregateType;
        private final boolean rootResource;

        DirectRoute(String aggregateType, boolean rootResource) {
            this.aggregateType = aggregateType;
            this.rootResource = rootResource;
        }

        boolean isRootResource() {
            return rootResource;
        }

        String aggregateType() {
            return aggregateType;
        }
    }

    public enum ResponseKind {
        CREATED(201), OK(200), NO_CONTENT(204);
        private final int status;

        ResponseKind(int status) {
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private record ResolvedRoute(DirectRoute route, String pathId) {
    }

    private record CommandSpec(String type, String aggregateType, String aggregateId, ObjectNode payload,
                               ResponseKind responseKind) {
    }

    public record Prepared(String method, String route, byte[] rawBody, PushPreflight.Mutation mutation,
                           String aggregateType, String aggregateId, ResponseKind responseKind) {
        public Prepared {
            rawBody = rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }
}
