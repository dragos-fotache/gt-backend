package com.dragosfotache.gtbackend.sync;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict whole-request structural validator and exact mutation-byte extractor.
 */
@Component
public final class PushPreflight {
    private static final JsonFactory F = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
    private static final ObjectMapper M = new ObjectMapper(F);
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> TYPES = Set.of("workout.start", "workout.upsert", "workout.finish", "workout.performAgain", "routine.startWorkout", "workout.saveAsRoutine", "workout.addExercises", "workout.replaceExercise", "workout.deleteExercise", "workout.deleteSet", "routine.upsert", "routine.save", "routine.deleteExercise", "routine.deleteSet", "customExercise.upsert", "aggregate.delete", "aggregate.import");

    public List<Mutation> parse(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            JsonNode root;
            try (JsonParser document = F.createParser(bytes)) {
                root = M.readTree(document);
                if (root == null) throw invalid();
                if (!root.isObject()) throw validation();
                if (document.nextToken() != null) throw invalid();
            }
            obj(root, "mutations");
            JsonNode a = root.get("mutations");
            if (a == null || !a.isArray() || a.size() > 100) throw validation();
            ArrayList<Mutation> out = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            long[] spans = spans(bytes, a.size());
            for (int i = 0; i < a.size(); i++) {
                JsonNode n = a.get(i);
                if (!n.isObject()) throw validation();
                ObjectNode o = (ObjectNode) n;
                obj(o, "mutationId", "type", "aggregateType", "aggregateId", "localGeneration", "knownServerRevision", "payload");
                String id = str(o, "mutationId"), t = str(o, "type"), ag = str(o, "aggregateType");
                if (!TYPES.contains(t)) throw validation();
                if (!Set.of("workout", "routine", "customExercise").contains(ag)) throw validation();
                uuid(o.get("mutationId"));
                uuid(o.get("aggregateId"));
                positive(o.get("localGeneration"));
                if (o.has("knownServerRevision") && !o.get("knownServerRevision").isNull())
                    positive(o.get("knownServerRevision"));
                if (!ids.add(id)) throw new Failure(422, "duplicate_mutation_id");
                if (!o.path("payload").isObject()) throw validation();
                validateMutation(t, ag, o.get("aggregateId").textValue(), (ObjectNode) o.get("payload"));
                out.add(new Mutation(id, Arrays.copyOfRange(bytes, (int) spans[2 * i], (int) spans[2 * i + 1])));
            }
            return List.copyOf(out);
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static long[] spans(byte[] b, int count) throws Exception {
        long[] out = new long[count * 2];
        try (JsonParser p = F.createParser(b)) {
            JsonToken t;
            while ((t = p.nextToken()) != null && t != JsonToken.START_ARRAY) {
            }
            if (t == null) throw invalid();
            for (int i = 0; i < count; i++) {
                if (p.nextToken() != JsonToken.START_OBJECT) throw validation();
                out[2 * i] = p.getTokenLocation().getByteOffset();
                p.skipChildren();
                out[2 * i + 1] = p.getCurrentLocation().getByteOffset();
            }
        }
        return out;
    }

    private static void validateMutation(String t, String ag, String target, ObjectNode p) {
        switch (t) {
            case "workout.start" -> {
                obj(p, "id", "name", "startedAt");
                commonId(p, "id", target);
                str(p, "name");
                date(p.get("startedAt"));
            }
            case "workout.upsert" -> workout(p, false, target);
            case "workout.finish" -> {
                obj(p, "finishedAt");
                date(p.get("finishedAt"));
            }
            case "workout.performAgain" -> {
                obj(p, "sourceWorkoutId", "id", "startedAt", "childIds");
                uuid(p.get("sourceWorkoutId"));
                commonId(p, "id", target);
                if (target.equals(p.get("sourceWorkoutId").textValue())) throw validation();
                date(p.get("startedAt"));
                cloneIds(p.get("childIds"), target);
            }
            case "routine.startWorkout" -> {
                obj(p, "sourceRoutineId", "id", "startedAt", "childIds");
                uuid(p.get("sourceRoutineId"));
                commonId(p, "id", target);
                date(p.get("startedAt"));
                cloneIds(p.get("childIds"), target);
            }
            case "workout.saveAsRoutine" -> {
                obj(p, "sourceWorkoutId", "id", "name", "childIds");
                uuid(p.get("sourceWorkoutId"));
                commonId(p, "id", target);
                if (str(p, "name").isEmpty()) throw validation();
                cloneIds(p.get("childIds"), target);
            }
            case "workout.addExercises" -> {
                obj(p, "additions");
                Set<String> ids = new HashSet<>();
                ids.add(target);
                arr(p.get("additions"), true).forEach(x -> {
                    obj(x, "id", "exerciseId", "setIds");
                    uuid(x.get("id"));
                    distinct(ids, x.get("id"));
                    uuid(x.get("exerciseId"));
                    arr(x.get("setIds"), true).forEach(s -> {
                        uuid(s);
                        distinct(ids, s);
                    });
                });
            }
            case "workout.replaceExercise" -> {
                obj(p, "workoutExerciseId", "exerciseId");
                uuid(p.get("workoutExerciseId"));
                uuid(p.get("exerciseId"));
            }
            case "workout.deleteExercise" -> {
                obj(p, "workoutExerciseId");
                uuid(p.get("workoutExerciseId"));
            }
            case "workout.deleteSet" -> {
                obj(p, "workoutSetId");
                uuid(p.get("workoutSetId"));
            }
            case "routine.upsert" -> routine(p, target);
            case "routine.save" -> {
                obj(p, "name");
                if (str(p, "name").isEmpty()) throw validation();
            }
            case "routine.deleteExercise" -> {
                obj(p, "routineExerciseId");
                uuid(p.get("routineExerciseId"));
            }
            case "routine.deleteSet" -> {
                obj(p, "routineSetId");
                uuid(p.get("routineSetId"));
            }
            case "customExercise.upsert" -> custom(p, target);
            case "aggregate.delete" -> {
                if (!p.isEmpty()) throw validation();
            }
            case "aggregate.import" -> {
                obj(p, "record");
                if (!p.path("record").isObject()) throw validation();
                if (ag.equals("workout")) workout((ObjectNode) p.get("record"), true, target);
                else if (ag.equals("routine")) routine((ObjectNode) p.get("record"), target);
                else custom((ObjectNode) p.get("record"), target);
            }
        }
        String expected = switch (t) {
            case "workout.saveAsRoutine", "routine.upsert", "routine.save", "routine.deleteExercise",
                 "routine.deleteSet" -> "routine";
            case "customExercise.upsert" -> "customExercise";
            case "aggregate.delete", "aggregate.import" -> ag;
            default -> "workout";
        };
        if (!ag.equals(expected)) throw validation();
    }

    private static void workout(ObjectNode o, boolean importing, String target) {
        obj(o, "id", "name", "status", "startedAt", "finishedAt", "exercises");
        commonId(o, "id", target);
        str(o, "name");
        if (!Set.of("active", "finished").contains(str(o, "status"))) throw validation();
        if (!importing && !o.get("status").textValue().equals("active")) throw validation();
        date(o.get("startedAt"));
        if (importing) {
            if (o.has("finishedAt") && !o.get("finishedAt").isNull()) date(o.get("finishedAt"));
        } else if (o.has("finishedAt")) throw validation();
        List<JsonNode> ex = arr(o.get("exercises"), false);
        HashSet<String> identities = new HashSet<>();
        identities.add(target);
        for (int i = 0; i < ex.size(); i++) {
            JsonNode e = ex.get(i);
            obj(e, "id", "exerciseId", "position", "notes", "restDurationSeconds", "sets");
            uuid(e.get("id"));
            distinct(identities, e.get("id"));
            uuid(e.get("exerciseId"));
            eqInt(e.get("position"), i, 0, Integer.MAX_VALUE);
            str(e, "notes");
            int32(e.get("restDurationSeconds"), 0);
            List<JsonNode> sets = arr(e.get("sets"), false);
            for (int j = 0; j < sets.size(); j++) {
                JsonNode s = sets.get(j);
                obj(s, "id", "position", "repetitions", "weightKg", "isCompleted", "restSeconds");
                uuid(s.get("id"));
                distinct(identities, s.get("id"));
                eqInt(s.get("position"), j, 0, Integer.MAX_VALUE);
                if (s.has("repetitions")) nullableInt(s.get("repetitions"), 0);
                if (s.has("weightKg")) nullableDouble(s.get("weightKg"));
                bool(s.get("isCompleted"));
                if (s.has("restSeconds")) nullableInt(s.get("restSeconds"), 0);
            }
        }
    }

    private static void routine(ObjectNode o, String target) {
        obj(o, "id", "name", "isDraft", "exercises");
        commonId(o, "id", target);
        str(o, "name");
        bool(o.get("isDraft"));
        List<JsonNode> ex = arr(o.get("exercises"), false);
        HashSet<String> ids = new HashSet<>();
        ids.add(target);
        for (int i = 0; i < ex.size(); i++) {
            JsonNode e = ex.get(i);
            obj(e, "id", "exerciseId", "position", "restDurationSeconds", "sets");
            uuid(e.get("id"));
            distinct(ids, e.get("id"));
            uuid(e.get("exerciseId"));
            eqInt(e.get("position"), i, 0, Integer.MAX_VALUE);
            int32(e.get("restDurationSeconds"), 0);
            List<JsonNode> sets = arr(e.get("sets"), false);
            for (int j = 0; j < sets.size(); j++) {
                JsonNode s = sets.get(j);
                obj(s, "id", "position", "repetitions", "weightKg");
                uuid(s.get("id"));
                distinct(ids, s.get("id"));
                eqInt(s.get("position"), j, 0, Integer.MAX_VALUE);
                if (s.has("repetitions")) nullableInt(s.get("repetitions"), 0);
                if (s.has("weightKg")) nullableDouble(s.get("weightKg"));
            }
        }
    }

    private static void custom(ObjectNode o, String target) {
        obj(o, "id", "name", "primaryMuscleGroupId", "secondaryMuscleGroupIds", "equipmentId", "equipmentNumItems", "category", "difficultyLevel", "forceType", "mechanics", "instructions");
        commonId(o, "id", target);
        if (str(o, "name").isEmpty()) throw validation();
        if (o.has("primaryMuscleGroupId")) nullableUuid(o.get("primaryMuscleGroupId"));
        if (o.has("secondaryMuscleGroupIds")) {
            HashSet<String> s = new HashSet<>();
            arr(o.get("secondaryMuscleGroupIds"), false).forEach(x -> {
                uuid(x);
                if (!s.add(x.textValue())) throw validation();
            });
            if (o.has("primaryMuscleGroupId") && !o.get("primaryMuscleGroupId").isNull() && s.contains(o.get("primaryMuscleGroupId").textValue()))
                throw validation();
        }
        if (o.has("equipmentId")) nullableUuid(o.get("equipmentId"));
        if (o.has("equipmentNumItems")) nullableInt(o.get("equipmentNumItems"), 0);
        if (o.has("equipmentNumItems") && !o.get("equipmentNumItems").isNull() && (!o.has("equipmentId") || o.get("equipmentId").isNull()))
            throw validation();
        for (String k : List.of("category", "difficultyLevel", "forceType", "mechanics"))
            if (o.has(k)) nullableString(o.get(k));
        if (o.has("instructions") && !o.get("instructions").isNull())
            arr(o.get("instructions"), false).forEach(x -> text(x));
    }

    private static void cloneIds(JsonNode n, String root) {
        List<JsonNode> es = arr(n, false);
        Set<String> ids = new HashSet<>(), sources = new HashSet<>();
        ids.add(root);
        for (JsonNode e : es) {
            obj(e, "sourceExerciseId", "id", "sets");
            uuid(e.get("sourceExerciseId"));
            if (!sources.add(e.get("sourceExerciseId").textValue())) throw validation();
            uuid(e.get("id"));
            distinct(ids, e.get("id"));
            for (JsonNode s : arr(e.get("sets"), false)) {
                obj(s, "sourceSetId", "id");
                uuid(s.get("sourceSetId"));
                if (!sources.add(s.get("sourceSetId").textValue())) throw validation();
                uuid(s.get("id"));
                distinct(ids, s.get("id"));
            }
        }
    }

    private static void commonId(JsonNode o, String key, String target) {
        uuid(o.get(key));
        if (!target.equals(o.get(key).textValue())) throw validation();
    }

    private static void distinct(Set<String> s, JsonNode n) {
        if (!s.add(n.textValue())) throw validation();
    }

    private static List<JsonNode> arr(JsonNode n, boolean nonempty) {
        if (n == null || !n.isArray() || (nonempty && n.isEmpty())) throw validation();
        ArrayList<JsonNode> r = new ArrayList<>();
        n.forEach(r::add);
        return r;
    }

    private static void obj(JsonNode n, String... allowed) {
        if (n == null || !n.isObject()) throw validation();
        Set<String> a = Set.of(allowed);
        n.fieldNames().forEachRemaining(k -> {
            if (!a.contains(k)) throw validation();
        });
    }

    private static String str(JsonNode o, String k) {
        return text(o.get(k));
    }

    private static String text(JsonNode n) {
        if (n == null || !n.isTextual()) throw validation();
        return n.textValue();
    }

    private static void nullableString(JsonNode n) {
        if (!n.isNull()) text(n);
    }

    private static void bool(JsonNode n) {
        if (n == null || !n.isBoolean()) throw validation();
    }

    private static void uuid(JsonNode n) {
        if (n == null || !n.isTextual() || !UUID.matcher(n.textValue()).matches()) throw validation();
    }

    private static void nullableUuid(JsonNode n) {
        if (!n.isNull()) uuid(n);
    }

    private static void positive(JsonNode n) {
        integer(n, 1, Long.MAX_VALUE);
    }

    private static void int32(JsonNode n, int min) {
        integer(n, min, Integer.MAX_VALUE);
    }

    private static void integer(JsonNode n, long min, long max) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < min || n.longValue() > max)
            throw validation();
    }

    private static void nullableInt(JsonNode n, int min) {
        if (!n.isNull()) int32(n, min);
    }

    private static void eqInt(JsonNode n, int expected, int min, int max) {
        integer(n, min, max);
        if (n.intValue() != expected) throw validation();
    }

    private static void nullableDouble(JsonNode n) {
        if (n.isNull()) return;
        if (!n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) throw validation();
    }

    private static void date(JsonNode n) {
        if (n == null || !n.isTextual()) throw validation();
        try {
            Instant i = OffsetDateTime.parse(n.textValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            if (i.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || i.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")) || i.getNano() % 1_000_000 != 0)
                throw validation();
        } catch (DateTimeException e) {
            throw validation();
        }
    }

    private static Failure invalid() {
        return new Failure(400, "invalid_request");
    }

    private static Failure validation() {
        return new Failure(422, "validation_error");
    }

    public record Mutation(String id, byte[] raw) {
        public Mutation {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }

    public static final class Failure extends RuntimeException {
        public final int status;
        public final String code;

        public Failure(int s, String c) {
            status = s;
            code = c;
        }
    }
}
