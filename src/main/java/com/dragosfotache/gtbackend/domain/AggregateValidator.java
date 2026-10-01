package com.dragosfotache.gtbackend.domain;

import com.dragosfotache.gtbackend.domain.CanonicalAggregates.*;

import java.time.Instant;
import java.util.*;

/**
 * Pure structural validation shared by aggregate persistence adapters.
 */
public final class AggregateValidator {
    private AggregateValidator() {
    }

    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z"), MAX = Instant.parse("9999-12-31T23:59:59.999Z");

    public static void workout(Workout w, boolean importing) {
        Objects.requireNonNull(w);
        Objects.requireNonNull(w.id());
        Objects.requireNonNull(w.name());
        Objects.requireNonNull(w.status());
        timestamp(w.startedAt());
        if (!importing && w.status() != WorkoutStatus.ACTIVE) fail("ordinary workout writes must be active");
        if ((w.status() == WorkoutStatus.ACTIVE) != (w.finishedAt() == null)) fail("status/finishedAt mismatch");
        if (w.revision() != 0) fail("write input cannot assign server revision");
        if (w.finishedAt() != null) timestamp(w.finishedAt());
        ids(w.id(), w.exercises().stream().map(WorkoutExercise::id).toList());
        order(w.exercises().stream().map(WorkoutExercise::position).toList());
        Set<UUID> family = new HashSet<>();
        family.add(w.id());
        family.addAll(w.exercises().stream().map(WorkoutExercise::id).toList());
        boolean completed = false, empty = false;
        for (var e : w.exercises()) {
            Objects.requireNonNull(e.exerciseId());
            Objects.requireNonNull(e.notes());
            nonnegative(e.restDurationSeconds());
            Objects.requireNonNull(e.sets());
            order(e.sets().stream().map(SetValue::position).toList());
            if (e.sets().isEmpty()) empty = true;
            for (var s : e.sets()) {
                Objects.requireNonNull(s);
                if (s.id() == null || !family.add(s.id())) fail("duplicate/null family identity");
                nonnegative(s.repetitions());
                nonnegative(s.restSeconds());
                finiteWeight(s.weightKg());
                if (s.completed() && (s.repetitions() == null || s.weightKg() == null))
                    fail("completed set needs reps and weight");
                completed |= s.completed();
                if (w.status() == WorkoutStatus.FINISHED && !s.completed()) fail("finished workout has incomplete set");
            }
        }
        if (w.status() == WorkoutStatus.FINISHED && (!completed || empty)) fail("invalid finished workout");
    }

    public static void routine(Routine r, boolean creating) {
        Objects.requireNonNull(r);
        Objects.requireNonNull(r.id());
        Objects.requireNonNull(r.name());
        Objects.requireNonNull(r.exercises());
        if (r.revision() != 0) fail("write input cannot assign server revision");
        if (creating && !r.draft()) fail("new routine must be draft");
        ids(r.id(), r.exercises().stream().map(RoutineExercise::id).toList());
        order(r.exercises().stream().map(RoutineExercise::position).toList());
        Set<UUID> family = new HashSet<>();
        family.add(r.id());
        r.exercises().forEach(e -> family.add(e.id()));
        for (var e : r.exercises()) {
            Objects.requireNonNull(e);
            Objects.requireNonNull(e.exerciseId());
            Objects.requireNonNull(e.sets());
            nonnegative(e.restDurationSeconds());
            order(e.sets().stream().map(RoutineSet::position).toList());
            for (var s : e.sets()) {
                Objects.requireNonNull(s);
                if (s.id() == null || !family.add(s.id())) fail("duplicate/null family identity");
                nonnegative(s.repetitions());
                finiteWeight(s.weightKg());
            }
        }
    }

    public static void savedRoutineEligible(Routine r) {
        if (blank(r.name()) || r.exercises().isEmpty()) fail("saved routine requires name and exercise");
    }

    public static void customExercise(CustomExercise e) {
        Objects.requireNonNull(e);
        Objects.requireNonNull(e.id());
        if (e.revision() != 0) fail("write input cannot assign server revision");
        Objects.requireNonNull(e.muscles());
        Objects.requireNonNull(e.equipment());
        if (blank(e.name())) fail("name is required");
        Set<UUID> muscleIds = new HashSet<>();
        UUID primary = null;
        for (var m : e.muscles()) {
            Objects.requireNonNull(m);
            Objects.requireNonNull(m.id());
            Objects.requireNonNull(m.role());
            if (!Set.of("primary", "secondary").contains(m.role())) fail("unsupported custom muscle role");
            if (!muscleIds.add(m.id())) fail("duplicate muscle");
            if (m.role().equals("primary")) {
                if (primary != null) fail("multiple primary muscles");
                primary = m.id();
            }
        }
        UUID primaryId = primary;
        if (primaryId != null && e.muscles().stream().anyMatch(m -> m.id().equals(primaryId) && m.role().equals("secondary")))
            fail("primary and secondary overlap");
        if (e.equipment().size() > 1) fail("multiple equipment selections");
        Set<UUID> eq = new HashSet<>();
        for (var x : e.equipment()) {
            Objects.requireNonNull(x);
            Objects.requireNonNull(x.id());
            if (!eq.add(x.id())) fail("duplicate equipment");
            nonnegative(x.quantity());
        }
        if (e.instructions() != null && e.instructions().stream().anyMatch(Objects::isNull)) fail("null instruction");
    }

    private static void timestamp(Instant i) {
        Objects.requireNonNull(i);
        if (i.isBefore(MIN) || i.isAfter(MAX) || i.getNano() % 1_000_000 != 0)
            fail("timestamp outside UTC millisecond range");
    }

    public static boolean blank(String v) {
        return v == null || v.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    private static void order(List<Integer> p) {
        Objects.requireNonNull(p);
        for (int i = 0; i < p.size(); i++)
            if (p.get(i) == null || p.get(i) != i) fail("positions must be consecutive from zero");
    }

    private static void ids(UUID root, List<UUID> ids) {
        Set<UUID> s = new HashSet<>();
        s.add(root);
        for (UUID id : ids) if (id == null || !s.add(id)) fail("duplicate/null family identity");
    }

    private static void nonnegative(Integer n) {
        if (n != null && n < 0) fail("negative integer");
    }

    private static void finiteWeight(Double d) {
        if (d != null && (!Double.isFinite(d) || d < 0)) fail("invalid weight");
    }

    private static void fail(String m) {
        throw new IllegalArgumentException(m);
    }
}
