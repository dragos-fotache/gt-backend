package com.dragosfotache.gtbackend.resource;

import com.dragosfotache.gtbackend.domain.CanonicalAggregates.*;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import com.dragosfotache.gtbackend.persistence.ResourceBrowseRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure mapping from fully resolved canonical read values to the public resource shape. */
public final class CanonicalResourceMapper {
    public Map<String, Object> workoutSummary(ResourceBrowseRepository.WorkoutSummary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", summary.id().toString());
        result.put("name", summary.name());
        result.put("status", summary.status());
        result.put("startedAt", summary.startedAt().toString());
        result.put("finishedAt", summary.finishedAt() == null ? null : summary.finishedAt().toString());
        result.put("revision", summary.revision());
        return result;
    }

    public Map<String, Object> workout(CanonicalAggregateRepository.WorkoutRead read) {
        Workout root = read.root();
        List<Map<String, Object>> exercises = read.exercises().stream().map(this::workoutExercise).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", root.id().toString());
        result.put("name", root.name());
        result.put("status", root.status().name().toLowerCase(Locale.ROOT));
        result.put("startedAt", root.startedAt().toString());
        result.put("finishedAt", root.finishedAt() == null ? null : root.finishedAt().toString());
        result.put("revision", root.revision());
        result.put("exercises", exercises);
        return result;
    }

    private Map<String, Object> workoutExercise(CanonicalAggregateRepository.WorkoutExerciseRead row) {
        WorkoutExercise exercise = row.value();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", exercise.id().toString());
        result.put("exerciseId", exercise.exerciseId().toString());
        result.put("position", exercise.position());
        result.put("notes", exercise.notes());
        result.put("restDurationSeconds", exercise.restDurationSeconds());
        result.put("sets", exercise.sets().stream().map(this::workoutSet).toList());
        result.put("exerciseName", row.exerciseName());
        return result;
    }

    private Map<String, Object> workoutSet(SetValue set) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", set.id().toString());
        result.put("position", set.position());
        result.put("repetitions", set.repetitions());
        result.put("weightKg", set.weightKg());
        result.put("isCompleted", set.completed());
        result.put("restSeconds", set.restSeconds());
        return result;
    }

    public Map<String, Object> routine(CanonicalAggregateRepository.RoutineRead read) {
        Routine root = read.root();
        List<Map<String, Object>> exercises = read.exercises().stream().map(this::routineExercise).toList();
        return Map.of("id", root.id().toString(), "name", root.name(), "isDraft", root.draft(),
                "revision", root.revision(), "exercises", exercises);
    }

    private Map<String, Object> routineExercise(CanonicalAggregateRepository.RoutineExerciseRead row) {
        RoutineExercise exercise = row.value();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", exercise.id().toString());
        result.put("exerciseId", exercise.exerciseId().toString());
        result.put("position", exercise.position());
        result.put("restDurationSeconds", exercise.restDurationSeconds());
        result.put("sets", exercise.sets().stream().map(this::routineSet).toList());
        result.put("exerciseName", row.exerciseName());
        return result;
    }

    private Map<String, Object> routineSet(RoutineSet set) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", set.id().toString());
        result.put("position", set.position());
        result.put("repetitions", set.repetitions());
        result.put("weightKg", set.weightKg());
        return result;
    }

    public Map<String, Object> exercise(ExerciseValue exercise) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", exercise.id().toString());
        result.put("name", exercise.name());
        result.put("type", exercise.type());
        result.put("isUserCreated", exercise.userCreated());
        if (exercise.revision() != null) result.put("revision", exercise.revision());
        result.put("category", exercise.category());
        result.put("difficultyLevel", exercise.difficultyLevel());
        result.put("forceType", exercise.forceType());
        result.put("mechanics", exercise.mechanics());
        result.put("instructions", exercise.instructions());
        result.put("muscleGroups", exercise.muscles().stream().map(muscle -> Map.of(
                "id", muscle.id().toString(), "name", muscle.name(), "type", muscle.role())).toList());
        List<Map<String, Object>> equipment = new ArrayList<>();
        for (ExerciseEquipment item : exercise.equipment()) equipment.add(exerciseEquipment(item));
        result.put("equipment", equipment);
        return result;
    }

    private Map<String, Object> exerciseEquipment(ExerciseEquipment item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id().toString());
        result.put("name", item.name());
        result.put("numItems", item.quantity());
        return result;
    }
}
