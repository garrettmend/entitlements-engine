/** Integration-tests PostgreSQL Row-Level Security by proving tenants cannot see one another's reports. */
package com.platform.entitlements;

import com.platform.entitlements.domain.Report;
import com.platform.entitlements.domain.ReportRepository;
import com.platform.entitlements.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.spec.SecretKeySpec;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the test that actually matters for this whole project. It proves
 * isolation is enforced by Postgres itself, not just by application code
 * "remembering" to filter — by calling the plain, unfiltered
 * ReportRepository.findAll() as two different tenants and asserting each
 * only ever sees its own rows.
 *
 * Uses a REAL Postgres via Testcontainers rather than H2, because H2 does
 * not implement Postgres Row-Level Security — a test against H2 here would
 * pass even if the RLS policies were completely broken, which would be
 * worse than having no test at all.
 */
@SpringBootTest
@Testcontainers
@Import(TenantIsolationIT.TestSecurityConfig.class)
class TenantIsolationIT {

    /**
     * The full app context includes Spring Security's OAuth2 resource
     * server auto-config, which by default builds its JwtDecoder by
     * fetching Cognito's OIDC discovery document over the network at
     * context-startup time. That's a real network call this test has no
     * business making — this bean overrides it (Spring Boot backs off its
     * auto-configured JwtDecoder when one is already defined) with a local
     * HMAC-based decoder that's never actually invoked, since this test
     * exercises the repository/RLS layer directly and never goes through
     * the security filter chain.
     */
    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            SecretKeySpec key = new SecretKeySpec(
                    "test-only-secret-not-used-for-real-tokens".getBytes(), "HmacSHA256");
            return NimbusJwtDecoder.withSecretKey(key).build();
        }
    }

    // The username/password given here become the container's initdb
    // SUPERUSER (that's just how the official postgres image works) — so
    // this account is used ONLY for running Flyway migrations below, never
    // for the application's runtime queries. init-test-roles.sql runs
    // during container initdb, as this superuser, and creates the separate
    // non-superuser `entitlements_app` role that the test's actual
    // isolation-proving queries run as.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("entitlements")
            .withUsername("entitlements_owner")
            .withPassword("owner_password")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Runtime pool: the restricted, non-superuser role. Everything the
        // test asserts through ReportRepository goes through THIS role,
        // which is the only way the test can actually prove RLS is doing
        // the work rather than being silently bypassed.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "entitlements_app");
        registry.add("spring.datasource.password", () -> "app_runtime_password");

        // Migrations: the privileged owner role that can CREATE TABLE / CREATE POLICY.
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fresh UUIDs generated per test method (not static/shared constants):
    // the Testcontainers Postgres instance IS shared across test methods in
    // this class (that's what @Container + a static field means), so reusing
    // the same tenant id across tests would let one test's inserted rows
    // pollute another test's row-count assertions.
    private final UUID TENANT_A = UUID.randomUUID();
    private final UUID TENANT_B = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void tenantCannotSeeAnotherTenantsReports() {
        seedTenant(TENANT_A);
        seedTenant(TENANT_B);

        TenantContext.setTenantId(TENANT_A.toString());
        Report reportA = new Report();
        reportA.setTenantId(TENANT_A);
        reportA.setTitle("Tenant A's confidential Q3 numbers");
        reportA.setCreatedBy("alice@tenant-a.com");
        reportRepository.save(reportA);
        TenantContext.clear();

        TenantContext.setTenantId(TENANT_B.toString());
        Report reportB = new Report();
        reportB.setTenantId(TENANT_B);
        reportB.setTitle("Tenant B's confidential Q3 numbers");
        reportB.setCreatedBy("bob@tenant-b.com");
        reportRepository.save(reportB);

        // Still tenant B's context: findAll() must return ONLY tenant B's report
        List<Report> visibleToB = reportRepository.findAll();
        assertThat(visibleToB).hasSize(1);
        assertThat(visibleToB.get(0).getTitle()).isEqualTo("Tenant B's confidential Q3 numbers");
        TenantContext.clear();

        // Switch to tenant A: must see ONLY tenant A's report, never B's
        TenantContext.setTenantId(TENANT_A.toString());
        List<Report> visibleToA = reportRepository.findAll();
        assertThat(visibleToA).hasSize(1);
        assertThat(visibleToA.get(0).getTitle()).isEqualTo("Tenant A's confidential Q3 numbers");
    }

    @Test
    void noTenantContextSeesNothingRatherThanEverything() {
        // Fail-closed check: a connection with app.current_tenant unset/empty
        // must match zero rows, never all rows. This is the case that would
        // fire if TenantFilter had a bug and let a request through without
        // ever calling TenantContext.setTenantId().
        seedTenant(TENANT_A);
        TenantContext.setTenantId(TENANT_A.toString());
        Report report = new Report();
        report.setTenantId(TENANT_A);
        report.setTitle("Should not be visible with no tenant set");
        report.setCreatedBy("alice@tenant-a.com");
        reportRepository.save(report);
        TenantContext.clear(); // deliberately leave no tenant set

        List<Report> visible = reportRepository.findAll();
        assertThat(visible).isEmpty();
    }

    private void seedTenant(UUID tenantId) {
        // Inserts directly via JdbcTemplate rather than TenantContext +
        // repository: `tenants` itself has no RLS policy (it's the parent
        // registry table, not tenant-owned data), and the container is
        // reused across test methods in this class, so this must be
        // idempotent.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "Tenant " + tenantId);
    }
}
