package com.platform.entitlements.abac;

import com.platform.entitlements.domain.Tenant;
import com.platform.entitlements.domain.TenantRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Registered as bean name "entitlements" (Spring's default bean name for
 * a class named EntitlementsService, lowercased) — this is exactly the
 * `@entitlements` referenced from `@entitlements.canAccess(tenantId, ...)`
 * in @PreAuthorize expressions on controller methods.
 */
@Component("entitlements")
public class EntitlementsService {

    private final TenantEntitlementsCache cache;
    private final TenantRepository tenantRepository;

    public EntitlementsService(TenantEntitlementsCache cache, TenantRepository tenantRepository) {
        this.cache = cache;
        this.tenantRepository = tenantRepository;
    }

    /**
     * @param tenantId the current request's tenant id, supplied by the
     *                 custom SpEL root (EntitlementsExpressionRoot) — see
     *                 that class's javadoc for why this comes from a custom
     *                 expression root rather than a plain method parameter.
     * @param permissionCode e.g. "CREATE_REPORT", "EXPORT_DATA" — looked up
     *                 against the static rule table in PermissionRule.
     */
    public boolean canAccess(String tenantId, String permissionCode) {
        PermissionRule rule = PermissionRule.forCode(permissionCode);
        UUID tenantUuid = UUID.fromString(tenantId);

        if (rule.requiredTier() != null) {
            SubscriptionTier tier = resolveTier(tenantUuid);
            if (!tier.atLeast(rule.requiredTier())) {
                return false;
            }
        }

        if (rule.requiredGroup() != null && !currentUserGroups().contains(rule.requiredGroup())) {
            return false;
        }

        return true;
    }

    /**
     * Cache-aside: check DynamoDB (or the in-memory test double) first;
     * on a miss, fall back to Postgres — the source of truth — and
     * repopulate the cache so the next call for this tenant hits.
     */
    private SubscriptionTier resolveTier(UUID tenantId) {
        return cache.get(tenantId).orElseGet(() -> {
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No tenant found for id " + tenantId + " — an authenticated request " +
                            "referenced a tenant id that doesn't exist in the tenant registry. " +
                            "This should be unreachable if TenantFilter and the JWT issuance " +
                            "process are both correct."));
            SubscriptionTier tier = SubscriptionTier.fromString(tenant.getSubscriptionTier());
            cache.put(tenantId, tier);
            return tier;
        });
    }

    /**
     * User-level attribute, read from the JWT's Cognito group membership —
     * NOT from a database lookup. Groups are assigned to a user at the
     * identity-provider level (Cognito console/API) and show up on every
     * token that user presents afterward, so checking them here is a pure
     * in-memory claim read, no extra I/O per request.
     */
    @SuppressWarnings("unchecked")
    private List<String> currentUserGroups() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return List.of();
            }
            String header = attributes.getRequest().getHeader("X-User-Groups");
            return header == null || header.isBlank() ? List.of() : List.of(header.split(","));
        }
        Object groups = jwt.getClaims().get("cognito:groups");
        return groups instanceof List ? (List<String>) groups : List.of();
    }
}
