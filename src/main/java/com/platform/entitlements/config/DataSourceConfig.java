package com.platform.entitlements.config;

import com.platform.entitlements.tenant.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    /**
     * Binds spring.datasource.* properties without creating the actual
     * DataSource bean yet — we need to wrap the real one below.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The real, physical, pooled Hikari DataSource. Flyway and any tooling
     * that needs a "normal" connection (e.g. migrations, which must run as
     * a privileged role BEFORE any tenant context exists) should point at
     * this bean directly rather than the tenant-aware wrapper.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * What the application (JPA/Hibernate, JdbcTemplate, everything else)
     * actually injects and uses. Every connection obtained through this
     * bean has app.current_tenant set from TenantContext for its lifetime.
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikariDataSource) {
        return new TenantAwareDataSource(hikariDataSource);
    }
}
