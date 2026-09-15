package com.platform.entitlements.abac;

import com.platform.entitlements.domain.Tenant;
import com.platform.entitlements.domain.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TenantAdminService {

    private final TenantRepository tenantRepository;
    private final TenantEntitlementsCache cache;

    public TenantAdminService(TenantRepository tenantRepository, TenantEntitlementsCache cache) {
        this.tenantRepository = tenantRepository;
        this.cache = cache;
    }

    /**
     * Updates the source of truth (Postgres) and then WRITES the cache
     * directly with the new value, rather than just evicting and letting
     * the next request repopulate it. Either would be correct, but writing
     * directly means the very first request after a tier change also gets
     * the fast (cache-hit) path instead of paying one Postgres round trip —
     * a small optimization, but free given we already know the new value.
     *
     * This runs in the same transaction as the Postgres write so a failed
     * cache write doesn't leave the two out of sync in a way that's hard to
     * reason about... except a cache write can't be rolled back by a DB
     * transaction rollback anyway (it's not a transactional resource). If
     * the Postgres commit fails after this method returns, the cache could
     * theoretically end up briefly ahead of Postgres. In practice this is
     * an acceptable risk for a periodic tier-change operation (not a hot
     * path), but it's the kind of thing worth calling out rather than
     * quietly ignoring — a stricter design would write the cache from an
     * AFTER_COMMIT listener, same pattern as MeteringEventPublishListener
     * in Part 2, at the cost of an extra moment of staleness.
     */
    @Transactional
    public void updateSubscriptionTier(UUID tenantId, SubscriptionTier newTier) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such tenant: " + tenantId));
        tenant.setSubscriptionTier(newTier.name());
        tenantRepository.save(tenant);
        cache.put(tenantId, newTier);
    }
}
