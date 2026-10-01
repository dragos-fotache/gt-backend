package com.dragosfotache.gtbackend.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal, wire-independent complete aggregate values. Null numeric inputs are preserved.
 */
public final class CanonicalAggregates {
    private CanonicalAggregates() {
    }

    public enum WorkoutStatus {ACTIVE, FINISHED}

    public record SetValue(UUID id, int position, Integer repetitions, Double weightKg, boolean completed,
                           Integer restSeconds) {
    }

    public record WorkoutExercise(UUID id, UUID exerciseId, int position, String notes, int restDurationSeconds,
                                  List<SetValue> sets) {
        public WorkoutExercise {
            sets = List.copyOf(sets);
        }
    }

    public record Workout(UUID id, String name, WorkoutStatus status, Instant startedAt, Instant finishedAt,
                          long revision, List<WorkoutExercise> exercises) {
        public Workout {
            exercises = List.copyOf(exercises);
        }
    }

    public record RoutineSet(UUID id, int position, Integer repetitions, Double weightKg) {
    }

    public record RoutineExercise(UUID id, UUID exerciseId, int position, int restDurationSeconds,
                                  List<RoutineSet> sets) {
        public RoutineExercise {
            sets = List.copyOf(sets);
        }
    }

    public record Routine(UUID id, String name, boolean draft, long revision, List<RoutineExercise> exercises) {
        public Routine {
            exercises = List.copyOf(exercises);
        }
    }

    public record Muscle(UUID id, String role) {
    }

    public record Equipment(UUID id, Integer quantity) {
    }

    public record ExerciseMuscle(UUID id, String name, String role) {
    }

    public record ExerciseEquipment(UUID id, String name, Integer quantity) {
    }

    public record ExerciseValue(UUID id, String name, String type, String category, String difficultyLevel,
                               String forceType, String mechanics, List<String> instructions, boolean userCreated,
                               Long revision, List<ExerciseMuscle> muscles, List<ExerciseEquipment> equipment) {
        public ExerciseValue {
            instructions = instructions == null ? null : List.copyOf(instructions);
            muscles = List.copyOf(muscles);
            equipment = List.copyOf(equipment);
        }
    }

    public record CustomExercise(UUID id, String name, String category, String difficultyLevel, String forceType,
                                 String mechanics, List<String> instructions, List<Muscle> muscles,
                                 List<Equipment> equipment, long revision) {
        public CustomExercise(UUID id, String name, String category, String difficultyLevel, String forceType, String mechanics, List<String> instructions, List<Muscle> muscles, List<Equipment> equipment) {
            this(id, name, category, difficultyLevel, forceType, mechanics, instructions, muscles, equipment, 0);
        }

        public CustomExercise {
            instructions = instructions == null ? null : List.copyOf(instructions);
            muscles = List.copyOf(muscles);
            equipment = List.copyOf(equipment);
        }
    }
}
