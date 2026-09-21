/** Runs Flyway migrations through a dedicated privileged database connection separate from runtime queries. */
package com.platform.entitlements.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs migrations using a DEDICATED connection built straight from
 * spring.flyway.url/user/password — deliberately NOT the same DataSource
 * the application uses at runtime (spring.datasource.*).
 *
 * This is the "two roles" version of the gotcha documented in
 * V2__enable_row_level_security.sql: migrations run as a privileged
 * schema-owning role (spring.flyway.user, e.g. entitlements_owner) that can
 * CREATE TABLE and CREATE POLICY, while the application's runtime pool
 * (spring.datasource.username, e.g. entitlements_app) is a separate,
 * non-superuser, non-owner role. Because that runtime role is neither the
 * owner nor a superuser, Postgres enforces RLS against it with no special
 * configuration needed — a stronger guarantee than leaning on
 * FORCE ROW LEVEL SECURITY as the only line of defense.
 */
@Configuration
public class FlywayConfig {

    @Bean
    @ConfigurationProperties("spring.flyway")
    public FlywayProperties flywayProperties() {
        return new FlywayProperties();
    }

    @Bean(initMethod = "migrate")
    public Flyway flyway(FlywayProperties flywayProperties) throws SQLException {
        repairSchemaHistory(flywayProperties);
        return Flyway.configure()
                .dataSource(flywayProperties.getUrl(), flywayProperties.getUser(), flywayProperties.getPassword())
                .locations(flywayProperties.getLocations().toArray(new String[0]))
                .load();
    }

    private void repairSchemaHistory(FlywayProperties properties) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUser(), properties.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'public'
                              AND table_name = 'flyway_schema_history'
                              AND column_name = 'installed_on'
                        ) THEN
                            ALTER TABLE public.flyway_schema_history
                                ALTER COLUMN installed_on SET DEFAULT CURRENT_TIMESTAMP;
                        END IF;
                    END
                    $$
                    """);
        }
    }
}
