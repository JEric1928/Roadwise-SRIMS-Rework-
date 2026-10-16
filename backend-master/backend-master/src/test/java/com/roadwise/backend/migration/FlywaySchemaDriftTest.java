package com.roadwise.backend.migration;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the real migration chain to a real PostgreSQL database, then asks Hibernate to
 * validate the JPA mappings against the schema that chain produced.
 *
 * <p>Why this exists: the test profile runs H2 with {@code ddl-auto=create-drop} and Flyway
 * disabled, so nothing in the default suite can ever notice that an entity and a migration
 * disagree. That drift is invisible until production starts with {@code ddl-auto=validate}
 * and refuses to boot. These tests close that hole by running both halves for real.</p>
 *
 * <p>Skipped unless {@code ROADWISE_TEST_PG_URL} is set, so the default build is unaffected.
 * Point it at a scratch database:</p>
 *
 * <pre>
 * ROADWISE_TEST_PG_URL=jdbc:postgresql://localhost:5432/roadwise_scratch \
 * ROADWISE_TEST_PG_USER=postgres ROADWISE_TEST_PG_PASSWORD=secret \
 *   ./mvnw -o test -Dtest=FlywaySchemaDriftTest
 * </pre>
 *
 * <p>PostGIS is optional: V13 degrades with a warning when the extension is absent, which
 * is itself worth running once against an extension-free database (CI, a fresh container).
 * The re-runnable-chain test is destructive by design and therefore needs a second opt-in,
 * {@code ROADWISE_TEST_PG_REPLAY=true}.</p>
 */
@EnabledIfEnvironmentVariable(named = "ROADWISE_TEST_PG_URL", matches = ".+")
class FlywaySchemaDriftTest {

    private static final String URL = System.getenv("ROADWISE_TEST_PG_URL");
    private static final String USER = environment().getOrDefault("ROADWISE_TEST_PG_USER", "postgres");
    private static final String PASSWORD = environment().getOrDefault("ROADWISE_TEST_PG_PASSWORD", "");

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");
    private static final Path COMPILED_ENTITIES = Paths.get("target/classes/com/roadwise/backend/model");
    private static final String MODEL_PACKAGE = "com.roadwise.backend.model.";

    private static java.util.Map<String, String> environment() {
        return System.getenv();
    }

    /**
     * Every {@code @Entity} the application actually loads, discovered from the compiled
     * classes rather than hand-listed: a hand-written list silently stops covering the
     * entity someone adds next, which is the failure mode this test exists to prevent.
     */
    private static List<Class<?>> modelEntities() throws Exception {
        assertTrue(Files.isDirectory(COMPILED_ENTITIES),
                "expected compiled entities in " + COMPILED_ENTITIES.toAbsolutePath()
                        + " — run this through Maven so target/classes is populated");

        List<Class<?>> entities = new ArrayList<>();
        try (Stream<Path> files = Files.list(COMPILED_ENTITIES)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                String className = MODEL_PACKAGE + name.substring(0, name.length() - ".class".length());
                Class<?> candidate = Class.forName(className);
                if (candidate.isAnnotationPresent(Entity.class)) {
                    entities.add(candidate);
                }
            }
        }

        // 16 mapped classes today. A failure here means the scan broke (or an entity was
        // deleted), not that the schema drifted: without entities the validation below
        // would pass vacuously.
        assertTrue(entities.size() >= 16,
                "only " + entities.size() + " mapped classes found; the entity scan is not seeing the model package");
        return entities;
    }

    /**
     * Mirrors the Flyway settings in application.properties, because the point is to
     * reproduce what deployment does: baseline a schema that has no history, then replay
     * the chain. Change one and change the other.
     */
    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static void validateEntitiesAgainstSchema() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", USER)
                .applySetting("hibernate.connection.password", PASSWORD)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : modelEntities()) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();
            // hbm2ddl.auto=validate runs here: a table, column or type the mappings expect
            // and the migrations do not create makes this throw.
            SessionFactory sessionFactory = metadata.buildSessionFactory();
            sessionFactory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static Set<String> recordedVersions() throws Exception {
        Set<String> versions = new LinkedHashSet<>();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    private static long failedMigrations() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success")) {
            row.next();
            return row.getLong(1);
        }
    }

    @Test
    void migrationsCreateEveryColumnTheEntitiesMap() throws Exception {
        flyway().migrate();

        validateEntitiesAgainstSchema();
    }

    @Test
    void everyMigrationFileIsRecordedAsApplied() throws Exception {
        flyway().migrate();

        Set<String> applied = recordedVersions();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("V") || !name.endsWith(".sql")) {
                    continue;
                }
                String version = name.substring(1, name.indexOf("__"));
                if (!applied.contains(version)) {
                    missing.add(name);
                }
            }
        }

        assertEquals(List.of(), missing, "these migrations are on disk but not recorded as applied");
        assertEquals(0, failedMigrations(), "a failed migration is sitting in flyway_schema_history");
    }

    /**
     * The scenario from B4: a database whose schema exists but whose Flyway history does not,
     * which is what {@code baseline-on-migrate=true} plus {@code baseline-version=0} means by
     * "adopt this schema". The whole chain is replayed against the schema it already produced,
     * so every non-idempotent statement in V1-V19 fails right here instead of in production.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ROADWISE_TEST_PG_REPLAY", matches = "true")
    void theChainIsReRunnableAgainstASchemaThatAlreadyExists() throws Exception {
        flyway().migrate();

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flyway_schema_history");
        }

        MigrateResult replay = flyway().migrate();

        assertTrue(replay.migrationsExecuted >= 19,
                "expected the whole chain to replay, only " + replay.migrationsExecuted + " migration(s) ran");
        assertEquals(0, failedMigrations());
        validateEntitiesAgainstSchema();
    }
}
