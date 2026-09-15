package com.platform.entitlements.abac;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active under the "test" profile in place of DynamoDbTenantEntitlementsCache
 * (which is @Profile("!test")). Deliberately has NO fallback-to-Postgres
 * logic of its own — tests that need to exercise the fallback path call
 * EntitlementsService directly with this cache empty and assert it reads
 * through to the (real, Testcontainers) Postgres tenants table, same as
 * production would on a genuine cache miss.
 */
@Component
@Profile("test")
public class InMemoryTenantEntitlementsCache implements TenantEntitlementsCache {

    private final Map<UUID, SubscriptionTier> store = new ConcurrentHashMap<>();

    @Override
    public Optional<SubscriptionTier> get(UUID tenantId) {
        return Optional.ofNullable(store.get(tenantId));
    }

    @Override
    public void put(UUID tenantId, SubscriptionTier tier) {
        store.put(tenantId, tier);
    }

    @Override
    public void evict(UUID tenantId) {
        store.remove(tenantId);
    }

    public void clear() {
        store.clear();
    }
}
