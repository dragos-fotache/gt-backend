package com.dragosfotache.gtbackend.persistence;

import com.dragosfotache.gtbackend.resource.BrowseQueries.ExerciseFilters;
import com.dragosfotache.gtbackend.resource.ListPageTokenService.WorkoutAnchor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Endpoint-specific keyset SQL. All methods join the caller's authorized page-read transaction. */
@Repository
public class ResourceBrowseRepository {
    private final JdbcTemplate jdbc;

    public ResourceBrowseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkoutSummary> workouts(UUID owner, String status, WorkoutAnchor after, int count) {
        StringBuilder sql = new StringBuilder("select id,name,status,started_at,finished_at,revision from workouts "
                + "where owner_id=? and deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(owner);
        if (status != null) {
            sql.append(" and status=?");
            args.add(status);
        }
        if (after != null) {
            sql.append(" and (started_at < ? or (started_at = ? and id < ?))");
            Timestamp startedAt = Timestamp.from(after.startedAt());
            args.add(startedAt);
            args.add(startedAt);
            args.add(after.id());
        }
        sql.append(" order by started_at desc,id desc limit ?");
        args.add(count);
        return jdbc.query(sql.toString(), (rs, row) -> new WorkoutSummary(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getLong("revision")), args.toArray());
    }

    public List<UUID> savedRoutineIds(UUID owner, UUID afterId, int count) {
        StringBuilder sql = new StringBuilder("select id from routines where owner_id=? and deleted_at is null "
                + "and is_draft=false");
        List<Object> args = new ArrayList<>();
        args.add(owner);
        if (afterId != null) {
            sql.append(" and id > ?");
            args.add(afterId);
        }
        sql.append(" order by id asc limit ?");
        args.add(count);
        return jdbc.query(sql.toString(), (rs, row) -> rs.getObject(1, UUID.class), args.toArray());
    }

    public List<ExerciseCandidate> exerciseCandidates(UUID owner, UUID afterId, ExerciseFilters filters, int count) {
        StringBuilder sql = new StringBuilder("select x.id,x.name,"
                + "array(select mg.name from exercise_muscle_groups emg join muscle_groups mg on mg.id=emg.muscle_group_id "
                + "where emg.exercise_id=x.id order by mg.id) as muscle_names,"
                + "array(select eq.name from exercise_equipment ee join equipment eq on eq.id=ee.equipment_id "
                + "where ee.exercise_id=x.id order by eq.id) as equipment_names "
                + "from exercises x where x.deleted_at is null and (x.owner_id is null or x.owner_id=?)");
        if (afterId != null) sql.append(" and x.id > ?");
        if (!filters.muscleGroupIds().isEmpty()) {
            sql.append(" and exists(select 1 from exercise_muscle_groups emg where emg.exercise_id=x.id "
                    + "and emg.muscle_group_id=any (?::uuid[]))");
        }
        if (!filters.equipmentIds().isEmpty()) {
            sql.append(" and exists(select 1 from exercise_equipment ee where ee.exercise_id=x.id "
                    + "and ee.equipment_id=any (?::uuid[]))");
        }
        sql.append(" order by x.id asc limit ?");

        return jdbc.query(connection -> bindExerciseCandidates(connection.prepareStatement(sql.toString()), owner,
                        afterId, filters, count), (rs, row) -> new ExerciseCandidate(
                        rs.getObject("id", UUID.class), rs.getString("name"), strings(rs.getArray("muscle_names")),
                        strings(rs.getArray("equipment_names"))));
    }

    private PreparedStatement bindExerciseCandidates(PreparedStatement statement, UUID owner, UUID afterId,
                                                       ExerciseFilters filters, int count) throws SQLException {
        int index = 1;
        statement.setObject(index++, owner);
        if (afterId != null) statement.setObject(index++, afterId);
        if (!filters.muscleGroupIds().isEmpty())
            statement.setArray(index++, statement.getConnection().createArrayOf("uuid", filters.muscleGroupIds().toArray()));
        if (!filters.equipmentIds().isEmpty())
            statement.setArray(index++, statement.getConnection().createArrayOf("uuid", filters.equipmentIds().toArray()));
        statement.setInt(index, count);
        return statement;
    }

    private static List<String> strings(Array value) throws SQLException {
        if (value == null) return List.of();
        try {
            Object raw = value.getArray();
            if (!(raw instanceof Object[] values)) return List.of();
            List<String> strings = new ArrayList<>(values.length);
            for (Object item : values) strings.add((String) item);
            return List.copyOf(strings);
        } finally {
            value.free();
        }
    }

    public record WorkoutSummary(UUID id, String name, String status, java.time.Instant startedAt,
                                 java.time.Instant finishedAt, long revision) {
    }

    public record ExerciseCandidate(UUID id, String name, List<String> muscleNames, List<String> equipmentNames) {
        public ExerciseCandidate {
            muscleNames = List.copyOf(muscleNames);
            equipmentNames = List.copyOf(equipmentNames);
        }

        public boolean matches(String normalizedSearch) {
            if (normalizedSearch == null) return true;
            if (name.toLowerCase(java.util.Locale.ROOT).contains(normalizedSearch)) return true;
            for (String muscleName : muscleNames)
                if (muscleName.toLowerCase(java.util.Locale.ROOT).contains(normalizedSearch)) return true;
            for (String equipmentName : equipmentNames)
                if (equipmentName.toLowerCase(java.util.Locale.ROOT).contains(normalizedSearch)) return true;
            return false;
        }
    }
}
