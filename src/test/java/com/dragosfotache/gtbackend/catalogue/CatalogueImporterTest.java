package com.dragosfotache.gtbackend.catalogue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class CatalogueImporterTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private record Fixture(JdbcTemplate jdbc, CatalogueImporter importer, TransactionTemplate tx) {
    }

    private Fixture fixture() {
        String schema = "test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new JdbcTemplate(admin).execute("create schema " + schema);
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl() + "&currentSchema=" + schema, postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        return new Fixture(jdbc, new CatalogueImporter(jdbc, new ObjectMapper()), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private void install(Fixture f) {
        f.tx.executeWithoutResult(s -> {
            try {
                f.importer.install();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static byte[] seed(String path) throws IOException {
        try (var in = CatalogueImporterTest.class.getClassLoader().getResourceAsStream("catalogue/v1/" + path)) {
            return in.readAllBytes();
        }
    }

    @Test
    void importsEveryMappedValueIndependentlyAndRepeatedInstallIsHarmless() throws Exception {
        Fixture f = fixture();
        install(f);
        ObjectMapper json = new ObjectMapper();
        JsonNode muscles = json.readTree(seed("en/muscle_groups.json"));
        JsonNode equipment = json.readTree(seed("en/equipment.json"));
        JsonNode exercises = json.readTree(seed("en/exercises.json"));
        assertEquals(17, muscles.size());
        assertEquals(36, equipment.size());
        assertEquals(899, exercises.size());
        Set<String> actual = new HashSet<>();
        f.jdbc.query("select id,slug,name from muscle_groups", rs -> {
            actual.add(rs.getObject(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        });
        Set<String> expected = new HashSet<>();
        for (JsonNode r : muscles)
            expected.add(r.path("id").asText() + "|" + r.path("slug").asText() + "|" + r.path("name").asText());
        assertEquals(expected, actual);
        actual.clear();
        expected.clear();
        f.jdbc.query("select id,name,type,usage_type from equipment", rs -> {
            actual.add(rs.getObject(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
        });
        for (JsonNode r : equipment)
            expected.add(r.path("id").asText() + "|" + r.path("name").asText() + "|" + r.path("type").asText() + "|" + nullable(r.path("usageType")));
        assertEquals(expected, actual);
        actual.clear();
        expected.clear();
        f.jdbc.query("select id,name,type,mechanics,category,instructions,difficulty_level,force_type,is_user_created from exercises", rs -> {
            String instructions = rs.getString("instructions");
            actual.add(rs.getObject("id") + "|" + rs.getString("name") + "|" + rs.getString("type") + "|" + nullable(rs.getString("mechanics")) + "|" + nullable(rs.getString("category")) + "|" + normalizeJson(instructions) + "|" + nullable(rs.getString("difficulty_level")) + "|" + nullable(rs.getString("force_type")) + "|" + rs.getBoolean("is_user_created"));
        });
        for (JsonNode r : exercises)
            expected.add(r.path("id").asText() + "|" + r.path("name").asText() + "|" + r.path("type").asText() + "|" + nullable(r.path("mechanics")) + "|" + nullable(r.path("category")) + "|" + normalizeJson(r.path("instructions").toString()) + "|" + nullable(r.path("difficultyLevel")) + "|" + nullable(r.path("forceType")) + "|false");
        assertEquals(expected, actual);
        actual.clear();
        expected.clear();
        f.jdbc.query("select exercise_id,muscle_group_id,type from exercise_muscle_groups", rs -> {
            actual.add(rs.getObject(1) + "|" + rs.getObject(2) + "|" + rs.getString(3));
        });
        for (JsonNode e : exercises)
            for (JsonNode r : e.path("muscleGroups"))
                expected.add(e.path("id").asText() + "|" + r.path("id").asText() + "|" + r.path("type").asText());
        assertEquals(expected, actual);
        assertEquals(2638, actual.size());
        actual.clear();
        expected.clear();
        f.jdbc.query("select exercise_id,equipment_id,num_items from exercise_equipment", rs -> {
            actual.add(rs.getObject(1) + "|" + rs.getObject(2) + "|" + (rs.getObject(3) == null ? "null" : rs.getObject(3).toString()));
        });
        for (JsonNode e : exercises)
            for (JsonNode r : e.path("equipment"))
                expected.add(e.path("id").asText() + "|" + r.path("id").asText() + "|" + (r.path("numItems").isNull() ? "null" : r.path("numItems").asText()));
        assertEquals(expected, actual);
        assertEquals(899, actual.size());
        install(f);
        assertEquals(899, count(f, "exercises"));
        // A future custom row and child relationship must not be treated as catalogue corruption.
        java.util.UUID customOwner = java.util.UUID.randomUUID();
        f.jdbc.update("insert into accounts(id) values (?)", customOwner);
        f.jdbc.update("insert into exercises(id,owner_id,name,type,is_user_created,created_at,updated_at,revision) values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',?,'custom','reps',true,now(),now(),1)", customOwner);
        f.jdbc.update("insert into exercise_muscle_groups(exercise_id,muscle_group_id,type) values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',(select id from muscle_groups limit 1),'primary')");
        f.jdbc.update("insert into exercise_equipment(exercise_id,equipment_id,num_items) values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',(select id from equipment limit 1),null)");
        install(f);
        assertEquals(900, count(f, "exercises"));
    }

    private static String nullable(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? "null" : n.isTextual() ? n.asText() : n.toString();
    }

    private static String normalizeJson(String value) {
        if (value == null) return "null";
        try {
            return new ObjectMapper().readTree(value).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String nullable(String n) {
        return n == null ? "null" : n;
    }

    @Test
    void publishedBundledCatalogueRowsAndRelationshipsAreReadOnly() {
        Fixture f = fixture();
        install(f);
        f.jdbc.update("insert into accounts(id) values (?)", java.util.UUID.randomUUID());
        assertThrows(RuntimeException.class, () -> f.jdbc.update("update exercises set owner_id=(select id from accounts limit 1),is_user_created=true,revision=1,created_at=now(),updated_at=now() where id=(select id from exercises where owner_id is null limit 1)"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("update muscle_groups set name='changed' where id=(select id from muscle_groups limit 1)"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("delete from equipment where id=(select id from equipment limit 1)"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("delete from exercise_muscle_groups where exercise_id=(select id from exercises where owner_id is null limit 1)"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("delete from exercise_equipment where exercise_id=(select id from exercises where owner_id is null limit 1)"));
        assertEquals(899, count(f, "exercises"));
    }

    @Test
    void tamperedInstalledValueFails() {
        Fixture f = fixture();
        install(f);
        f.jdbc.execute("alter table exercises disable trigger exercises_catalogue_read_only");
        f.jdbc.update("update exercises set name='tampered' where id=(select id from exercises limit 1)");
        f.jdbc.execute("alter table exercises enable trigger exercises_catalogue_read_only");
        assertThrows(RuntimeException.class, () -> install(f));
        assertEquals("tampered", f.jdbc.queryForObject("select name from exercises where name='tampered'", String.class));
    }

    @Test
    void conflictingPreexistingDataFailsWithoutPublishing() {
        Fixture f = fixture();
        f.jdbc.update("insert into muscle_groups(id,slug,name) values ('00000000-0000-0000-0000-000000000001','conflict','conflict')");
        assertThrows(RuntimeException.class, () -> install(f));
        assertEquals(0, count(f, "catalogue_versions"));
        assertEquals(1, count(f, "muscle_groups"));
        assertEquals(0, count(f, "equipment"));
    }

    @Test
    void malformedSeedFailsWithoutChangingDatabase() throws Exception {
        Fixture f = fixture();
        byte[] altered = seed("en/equipment.json");
        altered[0] = '!';
        CatalogueImporter bad = new CatalogueImporter(f.jdbc, new ObjectMapper(), p -> p.equals("en/equipment.json") ? altered : seed(p));
        assertThrows(RuntimeException.class, () -> f.tx.executeWithoutResult(s -> {
            try {
                bad.install();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        assertEquals(0, count(f, "catalogue_versions"));
        assertEquals(0, count(f, "muscle_groups"));
        assertEquals(0, count(f, "equipment"));
    }

    @Test
    void midImportDatabaseFailureRollsBackEarlierInserts() {
        Fixture f = fixture();
        f.jdbc.execute("create function reject_exercise() returns trigger language plpgsql as $$ begin raise exception 'injected failure'; end $$");
        f.jdbc.execute("create trigger reject_exercise before insert on exercises for each row execute function reject_exercise()");
        assertThrows(RuntimeException.class, () -> install(f));
        for (String table : new String[]{"muscle_groups", "equipment", "exercises", "exercise_muscle_groups", "exercise_equipment", "catalogue_versions"})
            assertEquals(0, count(f, table), table);
    }

    @Test
    void concurrentStartupPublishesExactlyOnce() throws Exception {
        Fixture f = fixture();
        var start = new CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                install(f);
                return null;
            });
            var b = pool.submit(() -> {
                start.await();
                install(f);
                return null;
            });
            start.countDown();
            a.get(90, TimeUnit.SECONDS);
            b.get(90, TimeUnit.SECONDS);
        }
        assertEquals(899, count(f, "exercises"));
        assertEquals(1, count(f, "catalogue_versions"));
    }

    private static long count(Fixture f, String table) {
        return f.jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
