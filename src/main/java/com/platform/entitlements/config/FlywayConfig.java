package com.platform.entitlements.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean(initMethod = "migrate")
    public Flyway flyway(FlywayProperties flywayProperties) {
        return Flyway.configure()
                .dataSource(flywayProperties.getUrl(), flywayProperties.getUser(), flywayProperties.getPassword())
                .locations(flywayProperties.getLocations().toArray(new String[0]))
                .baselineOnMigrate(true)
                .load();
    }
}
