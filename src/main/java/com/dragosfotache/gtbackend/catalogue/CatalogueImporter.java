package com.dragosfotache.gtbackend.catalogue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Installs the pinned, license-noticed catalogue packaged from shared-docs.
 */
@Component
public class CatalogueImporter {
    private static final String PREFIX = "catalogue/v1/";
    private static final String VERSION = "gymtracka-catalogue-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SeedSource seedSource;

    @FunctionalInterface
    interface SeedSource {
        byte[] read(String relativePath) throws IOException;
    }

    @Autowired
    public CatalogueImporter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, CatalogueImporter::classpathBytes);
    }

    CatalogueImporter(JdbcTemplate jdbc, ObjectMapper mapper, SeedSource seedSource) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.seedSource = seedSource;
    }

    @Transactional
    public void install() throws IOException {
        byte[] manifestBytes = bytes("manifest.json");
        JsonNode manifest = mapper.readTree(manifestBytes);
        require(VERSION.equals(manifest.path("catalogueVersion").asText()), "Unexpected catalogue version");
        Map<String, JsonNode> sources = new HashMap<>();
        for (String path : new String[]{"en/muscle_groups.json", "en/equipment.json", "en/exercises.json",
                "licenses/kinetic-place-MIT.txt", "licenses/wrkout-Unlicense.txt"}) {
            byte[] raw = bytes(path);
            JsonNode expected = manifest.path("files").path(path);
            require(raw.length == expected.path("bytes").asInt(-1)
                    && sha256(raw).equals(expected.path("sha256").asText()), "Catalogue checksum: " + path);
            if (path.endsWith(".json")) {
                JsonNode parsed = mapper.readTree(raw);
                require(parsed.isArray(), "Not an array: " + path);
                sources.put(path, parsed);
            }
        }
        JsonNode muscles = sources.get("en/muscle_groups.json");
        JsonNode tools = sources.get("en/equipment.json");
        JsonNode exercises = sources.get("en/exercises.json");
        checkCount(manifest, "muscle_groups", muscles.size());
        checkCount(manifest, "equipment", tools.size());
        checkCount(manifest, "exercises", exercises.size());
        validate(muscles, tools, exercises, manifest);

        // Serialize multiple application instances installing the same version, even with an empty table.
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(716348177)) locked", Integer.class);
        String installed = jdbc.query("SELECT manifest_sha256 FROM catalogue_versions WHERE version = ?",
                rs -> rs.next() ? rs.getString(1) : null, VERSION);
        if (installed != null) {
            require(installed.equals(sha256(manifestBytes)), "Different manifest installed under v1 name");
            verifyInstalled(muscles, tools, exercises);
            return;
        }
        // Never treat a partly populated database as a fresh installation.
        for (String table : new String[]{"muscle_groups", "equipment", "exercises",
                "exercise_muscle_groups", "exercise_equipment"}) {
            require(Objects.equals(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class), 0L),
                    "Catalogue table already populated without version: " + table);
        }
        for (JsonNode muscle : muscles) {
            jdbc.update("INSERT INTO muscle_groups (id, slug, name) VALUES (?, ?, ?)",
                    id(muscle), text(muscle, "slug"), text(muscle, "name"));
        }
        for (JsonNode tool : tools) {
            jdbc.update("INSERT INTO equipment (id, name, type, usage_type) VALUES (?, ?, ?, ?)",
                    id(tool), text(tool, "name"), text(tool, "type"), text(tool, "usageType"));
        }
        for (JsonNode exercise : exercises) {
            UUID exerciseId = id(exercise);
            JsonNode instructions = exercise.path("instructions");
            jdbc.update("INSERT INTO exercises (id, name, type, mechanics, category, instructions, "
                            + "difficulty_level, force_type) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                    exerciseId, text(exercise, "name"), text(exercise, "type"), text(exercise, "mechanics"),
                    text(exercise, "category"), instructions.isMissingNode() || instructions.isNull() ? null
                            : instructions.toString(), text(exercise, "difficultyLevel"), text(exercise, "forceType"));
            for (JsonNode muscle : exercise.path("muscleGroups")) {
                jdbc.update("INSERT INTO exercise_muscle_groups VALUES (?, ?, ?)",
                        exerciseId, id(muscle), text(muscle, "type"));
            }
            for (JsonNode tool : exercise.path("equipment")) {
                jdbc.update("INSERT INTO exercise_equipment VALUES (?, ?, ?)",
                        exerciseId, id(tool), tool.path("numItems").isNull() ? null : tool.path("numItems").asInt());
            }
        }
        jdbc.update("INSERT INTO catalogue_versions (version, manifest_sha256) VALUES (?, ?)",
                VERSION, sha256(manifestBytes));
    }

    private void verifyInstalled(JsonNode muscles, JsonNode tools, JsonNode exercises) {
        verifyRows("muscle_groups", muscles, "SELECT id, slug, name FROM muscle_groups",
                (source, row) -> id(source).equals(row.getObject("id", UUID.class))
                        && Objects.equals(text(source, "slug"), row.getString("slug"))
                        && Objects.equals(text(source, "name"), row.getString("name")));
        verifyRows("equipment", tools, "SELECT id, name, type, usage_type FROM equipment",
                (source, row) -> id(source).equals(row.getObject("id", UUID.class))
                        && Objects.equals(text(source, "name"), row.getString("name"))
                        && Objects.equals(text(source, "type"), row.getString("type"))
                        && Objects.equals(text(source, "usageType"), row.getString("usage_type")));
        verifyRows("exercises", exercises, "SELECT id, name, type, mechanics, category, instructions, difficulty_level, force_type, is_user_created FROM exercises WHERE is_user_created = false",
                (source, row) -> id(source).equals(row.getObject("id", UUID.class))
                        && Objects.equals(text(source, "name"), row.getString("name"))
                        && Objects.equals(text(source, "type"), row.getString("type"))
                        && Objects.equals(text(source, "mechanics"), row.getString("mechanics"))
                        && Objects.equals(text(source, "category"), row.getString("category"))
                        && jsonEquals(source.path("instructions"), row.getString("instructions"))
                        && Objects.equals(text(source, "difficultyLevel"), row.getString("difficulty_level"))
                        && Objects.equals(text(source, "forceType"), row.getString("force_type"))
                        && !row.getBoolean("is_user_created"));
        verifyRelationshipRows(exercises, true);
        verifyRelationshipRows(exercises, false);
    }

    @FunctionalInterface
    private interface RowCheck {
        boolean matches(JsonNode source, java.sql.ResultSet row) throws java.sql.SQLException;
    }

    private void verifyRows(String table, JsonNode expected, String sql, RowCheck check) {
        Map<UUID, JsonNode> byId = new HashMap<>();
        for (JsonNode item : expected) byId.put(id(item), item);
        Set<UUID> seen = new HashSet<>();
        jdbc.query(sql, row -> {
            UUID key = row.getObject("id", UUID.class);
            JsonNode source = byId.get(key);
            require(source != null && seen.add(key) && check.matches(source, row), "Installed value conflict: " + table + "/" + key);
        });
        require(seen.size() == byId.size(), "Installed rows missing: " + table);
    }

    private void verifyRelationshipRows(JsonNode exercises, boolean muscle) {
        String table = muscle ? "exercise_muscle_groups" : "exercise_equipment";
        Set<String> expected = new HashSet<>();
        for (JsonNode exercise : exercises)
            for (JsonNode relation : exercise.path(muscle ? "muscleGroups" : "equipment")) {
                expected.add(id(exercise) + ":" + id(relation) + ":" + (muscle ? text(relation, "type") :
                        relation.path("numItems").isNull() ? "<null>" : relation.path("numItems").asText()));
            }
        Set<String> actual = new HashSet<>();
        String sql = muscle ? "SELECT r.exercise_id, r.muscle_group_id, r.type AS value FROM exercise_muscle_groups r JOIN exercises e ON e.id=r.exercise_id WHERE e.is_user_created=false"
                : "SELECT r.exercise_id, r.equipment_id, r.num_items AS value FROM exercise_equipment r JOIN exercises e ON e.id=r.exercise_id WHERE e.is_user_created=false";
        jdbc.query(sql, row -> {
            actual.add(row.getObject("exercise_id", UUID.class) + ":"
                    + row.getObject(muscle ? "muscle_group_id" : "equipment_id", UUID.class) + ":"
                    + (row.getObject("value") == null ? "<null>" : row.getObject("value").toString()));
        });
        require(expected.equals(actual), "Installed relationship conflict: " + table);
    }

    private boolean jsonEquals(JsonNode expected, String stored) {
        if (expected.isMissingNode() || expected.isNull()) return stored == null;
        try {
            return mapper.readTree(stored).equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private void validate(JsonNode muscles, JsonNode tools, JsonNode exercises, JsonNode manifest) {
        Map<UUID, JsonNode> muscleById = indexed(muscles);
        Map<UUID, JsonNode> toolById = indexed(tools);
        indexed(exercises);
        Set<String> slugs = new HashSet<>();
        for (JsonNode muscle : muscles) {
            require(slugs.add(text(muscle, "slug")), "Duplicate muscle slug");
        }
        int muscleLinks = 0;
        int equipmentLinks = 0;
        for (JsonNode exercise : exercises) {
            JsonNode instructions = exercise.path("instructions");
            require(instructions.isNull() || (instructions.isArray() && allStrings(instructions)),
                    "Invalid instructions: " + id(exercise));
            Set<String> muscleKeys = new HashSet<>();
            Set<UUID> toolKeys = new HashSet<>();
            for (JsonNode muscle : exercise.path("muscleGroups")) {
                JsonNode canonical = muscleById.get(id(muscle));
                require(canonical != null && same(canonical, muscle, "name") && same(canonical, muscle, "slug"),
                        "Invalid muscle reference: " + id(exercise));
                require(muscleKeys.add(id(muscle) + ":" + text(muscle, "type")), "Duplicate muscle link");
                muscleLinks++;
            }
            for (JsonNode tool : exercise.path("equipment")) {
                JsonNode canonical = toolById.get(id(tool));
                require(canonical != null && same(canonical, tool, "name") && same(canonical, tool, "type")
                        && same(canonical, tool, "usageType"), "Invalid equipment reference: " + id(exercise));
                require(toolKeys.add(id(tool)), "Duplicate equipment link");
                require(tool.path("numItems").isNull() || (tool.path("numItems").canConvertToInt()
                        && tool.path("numItems").asInt() >= 0), "Invalid equipment quantity");
                equipmentLinks++;
            }
        }
        checkCount(manifest, "exercise_muscle_groups", muscleLinks);
        checkCount(manifest, "exercise_equipment", equipmentLinks);
    }

    private Map<UUID, JsonNode> indexed(JsonNode rows) {
        Map<UUID, JsonNode> indexed = new HashMap<>();
        for (JsonNode row : rows) {
            require(indexed.putIfAbsent(id(row), row) == null, "Duplicate catalogue ID: " + id(row));
        }
        return indexed;
    }

    private static boolean allStrings(JsonNode items) {
        for (JsonNode item : items) if (!item.isTextual()) return false;
        return true;
    }

    private static boolean same(JsonNode a, JsonNode b, String field) {
        return a.path(field).equals(b.path(field));
    }

    private static void checkCount(JsonNode manifest, String name, int count) {
        require(count == manifest.path("rowCounts").path(name).asInt(-1), "Catalogue row count: " + name);
    }

    private static UUID id(JsonNode row) {
        return UUID.fromString(row.path("id").asText());
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private byte[] bytes(String relativePath) throws IOException {
        return seedSource.read(relativePath);
    }

    private static byte[] classpathBytes(String relativePath) throws IOException {
        try (InputStream in = CatalogueImporter.class.getClassLoader().getResourceAsStream(PREFIX + relativePath)) {
            if (in == null) throw new IOException("Missing packaged catalogue: " + relativePath);
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
