package com.roadwise.backend.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in schema-history repair, standing in for the {@code spring.flyway.repair-on-migrate}
 * property this project used to set.
 *
 * <p>That key does not exist. Spring Boot's {@code FlywayProperties} carries no repair
 * switch and Flyway exposes {@code repair} only as an operation, so the property was
 * silently ignored while startup kept dying on the same checksum mismatch. This bean is
 * the switch that does work.
 *
 * <p>Repair rewrites the checksums recorded in {@code flyway_schema_history} for
 * migrations that were already applied, so they match the files bundled in this build.
 * That is the correct tool in exactly one situation: a shipped migration was edited
 * (9076a16 edited V6; e792ff0 edited V3, V5 and V6 to make them replay-safe) and the
 * deployed database never saw the edited file. Leaving it on permanently would mean any
 * edited or half-applied migration is accepted in silence instead of failing loudly, so
 * it stays off unless the deployment explicitly asks for it:
 *
 * <pre>
 *   FLYWAY_REPAIR_ON_STARTUP=true    # one release only, then remove the variable
 * </pre>
 *
 * <p>Without the flag the strategy is exactly Boot's default: validate, then migrate.
 */
@Configuration
public class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(
            @Value("${roadwise.flyway.repair-on-startup:false}") boolean repairOnStartup) {

        return flyway -> {
            if (repairOnStartup) {
                repair(flyway);
            }
            flyway.migrate();
        };
    }

    private static void repair(Flyway flyway) {
        log.warn("roadwise.flyway.repair-on-startup=true: realigning flyway_schema_history "
                + "checksums with the migrations bundled in this build before migrating. "
                + "Unset FLYWAY_REPAIR_ON_STARTUP once this release has started cleanly.");

        flyway.repair();
        log.warn("Flyway repair finished; validating and migrating the bundled migrations.");
    }
}
