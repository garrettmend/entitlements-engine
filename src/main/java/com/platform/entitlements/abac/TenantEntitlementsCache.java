package com.platform.entitlements.abac;

import java.util.Optional;
import java.util.UUID;

public interface TenantEntitlementsCache {

    Optional<SubscriptionTier> get(UUID tenantId);

    void put(UUID tenantId, SubscriptionTier tier);

    /** Called on write-through invalidation when a tenant's tier changes — see TenantAdminService. */
    void evict(UUID tenantId);
}
