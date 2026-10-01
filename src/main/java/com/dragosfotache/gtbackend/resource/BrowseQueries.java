package com.dragosfotache.gtbackend.resource;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/** Normalized immutable filter values shared by SQL matching and token context binding. */
public final class BrowseQueries {
    private BrowseQueries() {
    }

    public record ExerciseFilters(String search, List<UUID> muscleGroupIds, List<UUID> equipmentIds) {
        public ExerciseFilters {
            search = search == null || search.isEmpty() ? "" : search.toLowerCase(Locale.ROOT);
            muscleGroupIds = sortedDistinct(muscleGroupIds);
            equipmentIds = sortedDistinct(equipmentIds);
        }
    }

    public record PageOptions(int limit, String pageToken) {
        public PageOptions {
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid page limit");
        }
    }

    public static List<UUID> sortedDistinct(List<UUID> values) {
        TreeSet<UUID> sorted = new TreeSet<>(Comparator.comparing(UUID::toString));
        sorted.addAll(values);
        return List.copyOf(sorted);
    }
}
