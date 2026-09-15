package com.platform.entitlements.abac;

import com.platform.entitlements.domain.Tenant;
import com.platform.entitlements.domain.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito test of EntitlementsService.canAccess() — no Spring context,
 * no Postgres, no DynamoDB. The custom SpEL wiring that actually SUPPLIES
 * the tenantId argument in production (EntitlementsExpressionRoot /
 * EntitlementsMethodSecurityExpressionHandler) is NOT exercised here; that
 * requires a real @PreAuthorize-protected call, which ReportsAbacIT covers
 * end-to-end instead. This test exists purely to pin down the tier+group
 * boolean logic in isolation.
 */
class EntitlementsServiceTest {

    private final TenantEntitlementsCache cache = mock(TenantEntitlementsCache.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final EntitlementsService service = new EntitlementsService(cache, tenantRepository);

    private static final UUID TENANT_ID = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tierOnlyRuleGrantedWhenTenantMeetsMinimumTier() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.of(SubscriptionTier.PRO));
        setAuthenticatedGroups(List.of()); // no groups needed for EXPORT_DATA

        assertThat(service.canAccess(TENANT_ID.toString(), "EXPORT_DATA")).isTrue();
    }

    @Test
    void tierOnlyRuleDeniedWhenTenantBelowMinimumTier() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.of(SubscriptionTier.FREE));
        setAuthenticatedGroups(List.of());

        assertThat(service.canAccess(TENANT_ID.toString(), "EXPORT_DATA")).isFalse();
    }

    @Test
    void combinedRuleRequiresBothTierAndGroup() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.of(SubscriptionTier.ENTERPRISE));
        setAuthenticatedGroups(List.of("REPORT_WRITER"));

        assertThat(service.canAccess(TENANT_ID.toString(), "CREATE_REPORT")).isTrue();
    }

    @Test
    void combinedRuleDeniedWhenTierSufficientButGroupMissing() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.of(SubscriptionTier.ENTERPRISE));
        setAuthenticatedGroups(List.of("SOME_OTHER_GROUP"));

        assertThat(service.canAccess(TENANT_ID.toString(), "CREATE_REPORT")).isFalse();
    }

    @Test
    void combinedRuleDeniedWhenGroupPresentButTierInsufficient() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.of(SubscriptionTier.PRO));
        setAuthenticatedGroups(List.of("REPORT_WRITER"));

        assertThat(service.canAccess(TENANT_ID.toString(), "CREATE_REPORT")).isFalse();
    }

    @Test
    void cacheMissFallsBackToPostgresAndRepopulatesCache() {
        when(cache.get(TENANT_ID)).thenReturn(Optional.empty());
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setSubscriptionTier("ENTERPRISE");
        tenant.setCreatedAt(Instant.now());
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        setAuthenticatedGroups(List.of("REPORT_WRITER"));

        assertThat(service.canAccess(TENANT_ID.toString(), "CREATE_REPORT")).isTrue();
        verify(cache).put(TENANT_ID, SubscriptionTier.ENTERPRISE);
    }

    @Test
    void unknownPermissionCodeThrowsRatherThanSilentlyDenying() {
        assertThat(catchThrowable(() -> service.canAccess(TENANT_ID.toString(), "NOT_A_REAL_PERMISSION")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void setAuthenticatedGroups(List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claims(claims -> claims.putAll(Map.of("cognito:groups", groups, "sub", "test-user")))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null));
    }
}
