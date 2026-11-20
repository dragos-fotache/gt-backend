package com.dragosfotache.gtbackend.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PushPreflightTest {
    private final PushPreflight parser = new PushPreflight();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String one(String m) {
        return "{\"mutations\":[" + m + "]}";
    }

    private static String envelope(String type, String aggregate, String payload) {
        return "{\"mutationId\":\"00000000-0000-0000-0000-000000000011\",\"type\":\"" + type + "\",\"aggregateType\":\"" + aggregate + "\",\"aggregateId\":\"00000000-0000-0000-0000-000000000012\",\"localGeneration\":1,\"payload\":" + payload + "}";
    }

    private static String start() {
        return "{\"mutationId\":\"00000000-0000-0000-0000-000000000001\",\"type\":\"workout.start\",\"aggregateType\":\"workout\",\"aggregateId\":\"00000000-0000-0000-0000-000000000002\",\"localGeneration\":1,\"payload\":{\"id\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"Lïft \\\"A\\\"\",\"startedAt\":\"2025-01-01T00:00:00Z\"}}";
    }

    private static String workout(String setFields) {
        return "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"W\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[{\"id\":\"00000000-0000-0000-0000-000000000013\",\"exerciseId\":\"00000000-0000-0000-0000-000000000014\",\"position\":0,\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[{\"id\":\"00000000-0000-0000-0000-000000000015\",\"position\":0,\"isCompleted\":false" + setFields + "}]}]}";
    }

    private static String routine(String setFields) {
        return "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"R\",\"isDraft\":true,\"exercises\":[{\"id\":\"00000000-0000-0000-0000-000000000013\",\"exerciseId\":\"00000000-0000-0000-0000-000000000014\",\"position\":0,\"restDurationSeconds\":30,\"sets\":[{\"id\":\"00000000-0000-0000-0000-000000000015\",\"position\":0" + setFields + "}]}]}";
    }

    private static String j(String s) {
        return "\"" + s + "\"";
    }

    static Stream<Arguments> mutationShapes() {
        String workoutRecord = "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"W\",\"status\":\"active\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"exercises\":[]}";
        String routineRecord = "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"R\",\"isDraft\":true,\"exercises\":[]}";
        String customRecord = "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"C\"}";
        String emptyChild = "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"startedAt\":\"2025-01-01T00:00:00Z\",\"childIds\":[]}";
        String[] mutations = {
                envelope("workout.start", "workout", "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"name\":\"W\",\"startedAt\":\"2025-01-01T00:00:00Z\"}"),
                envelope("workout.startFromSnapshot", "workout", workout("")),
                envelope("workout.upsert", "workout", workoutRecord),
                envelope("workout.finish", "workout", "{\"finishedAt\":\"2025-01-01T00:00:00Z\"}"),
                envelope("workout.performAgain", "workout", emptyChild.substring(0, emptyChild.length() - 1) + ",\"sourceWorkoutId\":\"00000000-0000-0000-0000-000000000021\"}"),
                envelope("routine.startWorkout", "workout", emptyChild.substring(0, emptyChild.length() - 1) + ",\"sourceRoutineId\":\"00000000-0000-0000-0000-000000000021\"}"),
                envelope("workout.saveAsRoutine", "routine", "{\"id\":\"00000000-0000-0000-0000-000000000012\",\"sourceWorkoutId\":\"00000000-0000-0000-0000-000000000021\",\"name\":\"R\",\"childIds\":[]}"),
                envelope("workout.addExercises", "workout", "{\"additions\":[{\"id\":\"00000000-0000-0000-0000-000000000022\",\"exerciseId\":\"00000000-0000-0000-0000-000000000023\",\"setIds\":[\"00000000-0000-0000-0000-000000000024\"]}]}"),
                envelope("workout.replaceExercise", "workout", "{\"workoutExerciseId\":\"00000000-0000-0000-0000-000000000022\",\"exerciseId\":\"00000000-0000-0000-0000-000000000023\"}"),
                envelope("workout.deleteExercise", "workout", "{\"workoutExerciseId\":\"00000000-0000-0000-0000-000000000022\"}"),
                envelope("workout.deleteSet", "workout", "{\"workoutSetId\":\"00000000-0000-0000-0000-000000000022\"}"),
                envelope("routine.upsert", "routine", routineRecord),
                envelope("routine.save", "routine", "{\"name\":\"R\"}"),
                envelope("routine.deleteExercise", "routine", "{\"routineExerciseId\":\"00000000-0000-0000-0000-000000000022\"}"),
                envelope("routine.deleteSet", "routine", "{\"routineSetId\":\"00000000-0000-0000-0000-000000000022\"}"),
                envelope("customExercise.upsert", "customExercise", customRecord),
                envelope("aggregate.delete", "workout", "{}"),
                envelope("aggregate.import", "workout", "{\"record\":" + workoutRecord + "}"),
                envelope("aggregate.import", "routine", "{\"record\":" + routineRecord + "}"),
                envelope("aggregate.import", "customExercise", "{\"record\":" + customRecord + "}")
        };
        return Arrays.stream(mutations).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("mutationShapes")
    void allMutationKindsAndImportVariantsPassStructuralPreflight(String mutation) {
        assertEquals(1, parser.parse(b(one(mutation))).size());
    }

    @Test
    void capturesExactBytesAcrossObjectsUnicodeEscapesAndOuterWhitespace() {
        String a = start(), c = a.replace("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000003");
        String gotBody = "{ \"mutations\" : [ \n" + a + " \t,\n " + c + " ] }";
        var got = parser.parse(b(gotBody));
        assertArrayEquals(b(a), got.get(0).raw());
        assertArrayEquals(b(c), got.get(1).raw());
        assertArrayEquals(got.get(0).raw(), parser.parse(b(one(a))).getFirst().raw());
    }

    @Test
    void snapshotStartPreservesAcceptedBytesWithOptionalValuesAndZeroSets() {
        String input = workout(",\"repetitions\":null,\"weightKg\":0.0,\"restSeconds\":null")
                .replace("\"name\":\"W\"", "\"name\" : \" Lïft \"");
        String mutation = envelope("workout.startFromSnapshot", "workout", input);
        assertArrayEquals(b(mutation), parser.parse(b(one(mutation))).getFirst().raw());
        String zeroSets = input.substring(0, input.indexOf("\"sets\":")) + "\"sets\":[]}]}";
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("workout.startFromSnapshot", "workout", zeroSets)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSnapshotStarts")
    void snapshotStartRejectsInvalidStructure(String scenario, String mutation) {
        var failure = assertThrows(PushPreflight.Failure.class, () -> parser.parse(b(one(mutation))));
        assertEquals(422, failure.status);
        assertEquals("validation_error", failure.code);
    }

    static Stream<Arguments> invalidSnapshotStarts() {
        String input = workout("");
        String targetId = "00000000-0000-0000-0000-000000000012";
        String entryId = "00000000-0000-0000-0000-000000000013";
        String setId = "00000000-0000-0000-0000-000000000015";
        String mutation = envelope("workout.startFromSnapshot", "workout", input);
        return Stream.of(
                Arguments.of("wrong aggregate type", mutation.replace("\"aggregateType\":\"workout\"", "\"aggregateType\":\"routine\"")),
                Arguments.of("target differs from the payload ID", mutation.replace("\"aggregateId\":\"" + targetId, "\"aggregateId\":\"" + setId)),
                Arguments.of("malformed exercise child ID", mutation.replace(entryId, "bad-id")),
                Arguments.of("root and exercise IDs collide", mutation.replace(entryId, targetId)),
                Arguments.of("exercise and set IDs collide", mutation.replace(setId, entryId)),
                Arguments.of("positions must start at zero", mutation.replace("\"position\":0", "\"position\":1")),
                Arguments.of("set positions must start at zero", mutation.replace("\"position\":0,\"isCompleted\"", "\"position\":1,\"isCompleted\"")),
                Arguments.of("empty exercise list", envelope("workout.startFromSnapshot", "workout", input.substring(0, input.indexOf("\"exercises\"")) + "\"exercises\":[]}")),
                Arguments.of("empty name", mutation.replace("\"name\":\"W\"", "\"name\":\"\"")),
                Arguments.of("Unicode blank name", mutation.replace("\"name\":\"W\"", "\"name\":\" \\t 　\"")),
                Arguments.of("finished status", mutation.replace("\"active\"", "\"finished\"")),
                Arguments.of("completed initial set", mutation.replace("\"isCompleted\":false", "\"isCompleted\":true,\"repetitions\":1,\"weightKg\":0")),
                Arguments.of("recorded rest even when zero", envelope("workout.startFromSnapshot", "workout", workout(",\"restSeconds\":0"))),
                Arguments.of("unsupported finish timestamp", mutation.replace("\"status\":", "\"finishedAt\":null,\"status\":")),
                Arguments.of("unsupported routine binding", mutation.replace("\"status\":", "\"sourceRoutineId\":\"" + targetId + "\",\"status\":")),
                Arguments.of("unsupported canonical label", mutation.replace("\"notes\":", "\"exerciseName\":\"label\",\"notes\":")),
                Arguments.of("unsupported set field", envelope("workout.startFromSnapshot", "workout", workout(",\"revision\":1"))));
    }

    @Test
    void malformedEncodingJsonDuplicateKeysAndInvalidShapesHaveContractFailures() {
        assertEquals(400, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b("{bad"))).status);
        assertEquals(400, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b(one(start()) + " garbage"))).status);
        assertEquals(400, assertThrows(PushPreflight.Failure.class, () -> parser.parse(new byte[]{(byte) 0xc3, 0x28})).status);
        assertEquals(400, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b("{\"mutations\":[],\"mutations\":[]}"))).status);
        assertEquals(422, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b(one(start().replace("\"startedAt\":\"2025-01-01T00:00:00Z\"", "\"bogus\":1,\"startedAt\":\"2025-01-01T00:00:00Z\""))))).status);
        assertEquals("duplicate_mutation_id", assertThrows(PushPreflight.Failure.class, () -> parser.parse(b("{\"mutations\":[" + start() + "," + start() + "]}"))).code);
    }

    @Test
    void optionalSetValuesCanBeOmittedNullOrZeroAndWrongTypesAre422() {
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("workout.upsert", "workout", workout(""))))));
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("routine.upsert", "routine", routine(""))))));
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("workout.upsert", "workout", workout(",\"repetitions\":null,\"weightKg\":0,\"restSeconds\":null"))))));
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("routine.upsert", "routine", routine(",\"repetitions\":0,\"weightKg\":null"))))));
        assertEquals(422, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b(one(envelope("workout.upsert", "workout", workout(",\"repetitions\":\"4\"")))))).status);
    }

    @Test
    void activeImportMayOmitFinishedAt() {
        String record = workout("");
        record = record.substring(0, record.length() - 1) + ",\"finishedAt\":null}";
        record = record.replace(",\"finishedAt\":null", "");
        String payload = "{\"record\":" + record + "}";
        assertDoesNotThrow(() -> parser.parse(b(one(envelope("aggregate.import", "workout", payload)))));
    }

    @Test
    void enforcesEmptyAnd100MutationLimit() {
        assertTrue(parser.parse(b("{\"mutations\":[]}")).isEmpty());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++)
            many.add(start().replace("00000000-0000-0000-0000-000000000001", String.format("00000000-0000-0000-0000-%012d", i + 1)));
        assertEquals(100, parser.parse(b("{\"mutations\":[" + String.join(",", many) + "]}")).size());
        assertEquals(422, assertThrows(PushPreflight.Failure.class, () -> parser.parse(b("{\"mutations\":[" + String.join(",", many) + "," + start().replace("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000999") + "]}"))).status);
    }
}
