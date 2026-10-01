package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseEquipment;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseMuscle;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseValue;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.RoutineSet;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.SetValue;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository.RoutineRead;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository.WorkoutRead;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Pure projection from fully resolved canonical reads to the narrower OpenAPI export shapes.
 * Server revisions, sync internals, and credential fields are never emitted. No JSON library
 * with auto-close semantics is used; callers control every outward byte.
 */
public final class AccountExportMapper {
    private AccountExportMapper() {
    }

    public static String customExercise(ExerciseValue value) {
        if (value == null || !value.userCreated()) throw new IllegalStateException("export mapping fault");
        StringBuilder out = new StringBuilder();
        out.append("{\"id\":").append(quoted(value.id().toString()));
        out.append(",\"name\":").append(quoted(value.name()));
        out.append(",\"type\":").append(quoted(value.type()));
        out.append(",\"isUserCreated\":true");
        out.append(",\"category\":").append(nullable(value.category()));
        out.append(",\"difficultyLevel\":").append(nullable(value.difficultyLevel()));
        out.append(",\"forceType\":").append(nullable(value.forceType()));
        out.append(",\"mechanics\":").append(nullable(value.mechanics()));
        out.append(",\"instructions\":").append(strings(value.instructions()));
        out.append(",\"muscleGroups\":[");
        List<ExerciseMuscle> muscles = value.muscles();
        for (int i = 0; i < muscles.size(); i++) {
            if (i > 0) out.append(',');
            ExerciseMuscle muscle = muscles.get(i);
            out.append("{\"id\":").append(quoted(muscle.id().toString()));
            out.append(",\"name\":").append(quoted(muscle.name()));
            out.append(",\"type\":").append(quoted(muscle.role())).append('}');
        }
        out.append("],\"equipment\":[");
        List<ExerciseEquipment> equipment = value.equipment();
        for (int i = 0; i < equipment.size(); i++) {
            if (i > 0) out.append(',');
            ExerciseEquipment item = equipment.get(i);
            out.append("{\"id\":").append(quoted(item.id().toString()));
            out.append(",\"name\":").append(quoted(item.name()));
            out.append(",\"numItems\":").append(number(item.quantity())).append('}');
        }
        return out.append("]}").toString();
    }

    public static String routine(RoutineRead read) {
        if (read == null) throw new IllegalStateException("export mapping fault");
        StringBuilder out = new StringBuilder();
        out.append("{\"id\":").append(quoted(read.root().id().toString()));
        out.append(",\"name\":").append(quoted(read.root().name()));
        out.append(",\"isDraft\":").append(read.root().draft());
        out.append(",\"exercises\":[");
        for (int i = 0; i < read.exercises().size(); i++) {
            if (i > 0) out.append(',');
            var row = read.exercises().get(i);
            var exercise = row.value();
            out.append("{\"id\":").append(quoted(exercise.id().toString()));
            out.append(",\"exerciseId\":").append(quoted(exercise.exerciseId().toString()));
            out.append(",\"position\":").append(exercise.position());
            out.append(",\"restDurationSeconds\":").append(exercise.restDurationSeconds());
            out.append(",\"sets\":[");
            List<RoutineSet> sets = exercise.sets();
            for (int k = 0; k < sets.size(); k++) {
                if (k > 0) out.append(',');
                RoutineSet set = sets.get(k);
                out.append("{\"id\":").append(quoted(set.id().toString()));
                out.append(",\"position\":").append(set.position());
                out.append(",\"repetitions\":").append(number(set.repetitions()));
                out.append(",\"weightKg\":").append(decimal(set.weightKg())).append('}');
            }
            out.append("],\"exerciseName\":").append(quoted(row.exerciseName())).append('}');
        }
        return out.append("]}").toString();
    }

    public static String workout(WorkoutRead read) {
        if (read == null) throw new IllegalStateException("export mapping fault");
        StringBuilder out = new StringBuilder();
        out.append("{\"id\":").append(quoted(read.root().id().toString()));
        out.append(",\"name\":").append(quoted(read.root().name()));
        out.append(",\"status\":").append(quoted(read.root().status().name().toLowerCase(Locale.ROOT)));
        out.append(",\"startedAt\":").append(quoted(read.root().startedAt().toString()));
        out.append(",\"finishedAt\":").append(timestamp(read.root().finishedAt()));
        out.append(",\"exercises\":[");
        for (int i = 0; i < read.exercises().size(); i++) {
            if (i > 0) out.append(',');
            var row = read.exercises().get(i);
            var exercise = row.value();
            out.append("{\"id\":").append(quoted(exercise.id().toString()));
            out.append(",\"exerciseId\":").append(quoted(exercise.exerciseId().toString()));
            out.append(",\"position\":").append(exercise.position());
            out.append(",\"notes\":").append(quoted(exercise.notes()));
            out.append(",\"restDurationSeconds\":").append(exercise.restDurationSeconds());
            out.append(",\"sets\":[");
            List<SetValue> sets = exercise.sets();
            for (int k = 0; k < sets.size(); k++) {
                if (k > 0) out.append(',');
                SetValue set = sets.get(k);
                out.append("{\"id\":").append(quoted(set.id().toString()));
                out.append(",\"position\":").append(set.position());
                out.append(",\"repetitions\":").append(number(set.repetitions()));
                out.append(",\"weightKg\":").append(decimal(set.weightKg()));
                out.append(",\"isCompleted\":").append(set.completed());
                out.append(",\"restSeconds\":").append(number(set.restSeconds())).append('}');
            }
            out.append("],\"exerciseName\":").append(quoted(row.exerciseName())).append('}');
        }
        return out.append("]}").toString();
    }

    private static String quoted(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static String nullable(String value) {
        return value == null ? "null" : quoted(value);
    }

    private static String timestamp(Instant value) {
        return value == null ? "null" : quoted(value.toString());
    }

    private static String strings(List<String> values) {
        if (values == null) return "null";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(quoted(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String number(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String decimal(Double value) {
        if (value == null) return "null";
        if (!Double.isFinite(value)) throw new IllegalStateException("export mapping fault");
        return Double.toString(value);
    }
}
