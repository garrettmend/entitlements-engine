package com.platform.entitlements.domain;

import com.platform.entitlements.abac.SubscriptionTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This is the test that actually proves Part 3's "hard part" works — not
 * just EntitlementsService's boolean logic in isolation (that's
 * EntitlementsServiceTest), but the full chain: a real HTTP request with a
 * real JWT flows through Spring Security, EntitlementsMethodSecurityExpressionHandler
 * builds a custom SpEL root, extracts tenantId from the JWT claims, and
 * @entitlements.canAccess(tenantId, ...) on the controller method actually
 * gates the call correctly.
 *
 * Four scenarios matching the blueprint's own example almost exactly:
 * Enterprise+group succeeds, Enterprise without group fails, Pro with
 * group fails (tier insufficient despite having the group), and a
 * tier-only rule (EXPORT_DATA) succeeds for Pro with no group at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(ReportsAbacIT.TestSecurityConfig.class)
class ReportsAbacIT {

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            // Not actually used to decode anything in this test — MockMvc
            // requests use the jwt() RequestPostProcessor, which injects a
            // pre-built Authentication directly, bypassing real decoding.
            // This bean only exists so the app context doesn't try to hit
            // a real Cognito issuer over the network at startup.
            SecretKeySpec key = new SecretKeySpec(
                    "test-only-secret-not-used-for-real-tokens".getBytes(), "HmacSHA256");
            return NimbusJwtDecoder.withSecretKey(key).build();
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("entitlements")
            .withUsername("entitlements_owner")
            .withPassword("owner_password")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "entitlements_app");
        registry.add("spring.datasource.password", () -> "app_runtime_password");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID seedTenant(SubscriptionTier tier) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("ABAC Test Tenant " + tier);
        tenant.setSubscriptionTier(tier.name());
        tenant.setCreatedAt(Instant.now());
        tenantRepository.save(tenant);
        return tenant.getId();
    }

    @Test
    void enterpriseTenantWithReportWriterGroupCanCreateReport() throws Exception {
        UUID tenantId = seedTenant(SubscriptionTier.ENTERPRISE);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Q3 numbers\",\"body\":\"...\"}")
                        .with(jwt().jwt(b -> b
                                .claim("custom:tenant_id", tenantId.toString())
                                .claim("cognito:groups", List.of("REPORT_WRITER")))))
                .andExpect(status().isCreated());
    }

    @Test
    void enterpriseTenantWithoutReportWriterGroupIsForbidden() throws Exception {
        UUID tenantId = seedTenant(SubscriptionTier.ENTERPRISE);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Q3 numbers\",\"body\":\"...\"}")
                        .with(jwt().jwt(b -> b
                                .claim("custom:tenant_id", tenantId.toString())
                                .claim("cognito:groups", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void proTenantWithReportWriterGroupIsStillForbidden() throws Exception {
        // Has the group, but the tenant's tier isn't high enough — both
        // attributes are required, neither alone is sufficient.
        UUID tenantId = seedTenant(SubscriptionTier.PRO);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Q3 numbers\",\"body\":\"...\"}")
                        .with(jwt().jwt(b -> b
                                .claim("custom:tenant_id", tenantId.toString())
                                .claim("cognito:groups", List.of("REPORT_WRITER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void proTenantCanExportWithNoGroupRequirement() throws Exception {
        UUID tenantId = seedTenant(SubscriptionTier.PRO);

        mockMvc.perform(get("/reports/export")
                        .with(jwt().jwt(b -> b
                                .claim("custom:tenant_id", tenantId.toString())
                                .claim("cognito:groups", List.of()))))
                .andExpect(status().isOk());
    }

    @Test
    void freeTenantCannotExport() throws Exception {
        UUID tenantId = seedTenant(SubscriptionTier.FREE);

        mockMvc.perform(get("/reports/export")
                        .with(jwt().jwt(b -> b
                                .claim("custom:tenant_id", tenantId.toString())
                                .claim("cognito:groups", List.of()))))
                .andExpect(status().isForbidden());
    }
}
