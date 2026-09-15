package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the claim made in MeteringEventDao's javadoc: that two genuinely
 * concurrent duplicate requests (same tenant, same idempotency key) can
 * never both succeed in creating a row, WITHOUT relying on an application-
 * level lock or a "check then insert" pattern (which would itself have the
 * same race this whole mechanism exists to close).
 *
 * We fire N threads at insertIfAbsent() simultaneously (synchronized via a
 * CountDownLatch so they actually overlap rather than running sequentially)
 * and assert exactly one of them got a present Optional (won the race) and
 * exactly one row exists in the table afterward.
 */
@SpringBootTest
@Testcontainers
@Import(MeteringConcurrencyIT.TestSecurityConfig.class)
class MeteringConcurrencyIT {

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
    private MeteringEventDao dao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onlyOneOfManyConcurrentDuplicateInsertsSucceeds() throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "Concurrency Test Tenant");

        String sharedIdempotencyKey = "concurrent-key-" + UUID.randomUUID();
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<Optional<UUID>>> tasks = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                // TenantContext is per-thread, and this callable runs on a
                // worker thread from the pool, not the test's main thread —
                // it must set its own context, same as any real concurrent
                // request would via TenantFilter.
                TenantContext.setTenantId(tenantId.toString());
                try {
                    readyLatch.countDown();
                    startLatch.await(); // all threads block here until released together
                    return dao.insertIfAbsent(tenantId, sharedIdempotencyKey, "API_CALL", BigDecimal.ONE);
                } finally {
                    TenantContext.clear();
                }
            });
        }

        List<Future<Optional<UUID>>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await(5, TimeUnit.SECONDS); // wait for all threads to be parked at the latch
        startLatch.countDown(); // release them all at once, maximizing actual overlap

        long successCount = 0;
        for (Future<Optional<UUID>> future : futures) {
            if (future.get(10, TimeUnit.SECONDS).isPresent()) {
                successCount++;
            }
        }
        executor.shutdown();

        assertThat(successCount)
                .as("exactly one concurrent insert should have won the race")
                .isEqualTo(1);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM metering_events WHERE idempotency_key = ?",
                Integer.class, sharedIdempotencyKey);
        assertThat(rowCount).isEqualTo(1);
    }
}
