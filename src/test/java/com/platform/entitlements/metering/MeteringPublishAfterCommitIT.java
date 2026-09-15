package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves two things end-to-end against real Postgres + a real (fake)
 * ApplicationEventPublisher/@Async pipeline — NOT mocked, unlike
 * MeteringServiceTest:
 *
 * 1. The EventBridge publish (NoOpUsageEventPublisher standing in for it)
 *    actually happens, asynchronously, after MeteringService's transaction
 *    commits — proving the AFTER_COMMIT wiring in
 *    MeteringEventPublishListener is real, not just a comment.
 * 2. A replayed (duplicate idempotency key) call does NOT trigger a second
 *    publish — proving MeteringService's replay branch correctly skips
 *    re-publishing.
 *
 * Deliberately does NOT annotate the test class/methods with @Transactional
 * — Spring's test-managed transactions roll back at the end of each test by
 * default, which would mean the service's own @Transactional write never
 * actually commits, and AFTER_COMMIT listeners never fire. We need real
 * commits for this test to mean anything.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test") // activates NoOpUsageEventPublisher in place of the real EventBridge client
@Import(MeteringPublishAfterCommitIT.TestSecurityConfig.class)
class MeteringPublishAfterCommitIT {

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        JwtDecoder jwtDecoder() {
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
    private MeteringService meteringService;

    @Autowired
    private NoOpUsageEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        publisher.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "Publish Test Tenant");
        TenantContext.setTenantId(tenantId.toString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void newEventIsPublishedExactlyOnceEvenAfterReplays() throws InterruptedException {
        String idempotencyKey = "publish-test-" + UUID.randomUUID();

        meteringService.recordUsage(idempotencyKey, new RecordUsageRequest("API_CALL", BigDecimal.ONE));

        // The publish happens on a separate @Async thread after commit, so
        // it hasn't necessarily happened yet the instant recordUsage()
        // returns. Poll rather than assert immediately or sleep a fixed
        // "should be enough" duration.
        awaitPublishedCount(1, Duration.ofSeconds(5));

        // Two replays with the SAME idempotency key — neither should
        // trigger another publish.
        meteringService.recordUsage(idempotencyKey, new RecordUsageRequest("API_CALL", BigDecimal.ONE));
        meteringService.recordUsage(idempotencyKey, new RecordUsageRequest("API_CALL", BigDecimal.ONE));

        // Give any (incorrect) extra async publish a chance to land before
        // asserting it didn't.
        Thread.sleep(500);
        assertThat(publisher.published()).hasSize(1);
    }

    private void awaitPublishedCount(int expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (publisher.published().size() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(publisher.published())
                .as("expected async publish to have happened within %s", timeout)
                .hasSizeGreaterThanOrEqualTo(expected);
    }
}
