/** Periodically retries old unpublished events so committed usage eventually reaches the external publisher. */
package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Catches the crash window MeteringEventPublishListener can't cover on its
 * own: if the process dies between the DB transaction committing and the
 * async @TransactionalEventListener actually running (or while it's
 * mid-flight), that event's `published_at` stays NULL forever unless
 * something else comes looking for it. This job is that something else —
 * it's what actually makes "eventually published" a guarantee rather than
 * a best-effort.
 *
 * NOTABLE DESIGN CHOICE: this job runs across ALL tenants, but it does NOT
 * use any kind of RLS-bypass role to do it. Instead it fetches the list of
 * tenant ids (from `tenants`, which has no RLS policy — it's not
 * tenant-owned data, it's the tenant registry itself) and loops, setting
 * TenantContext for each tenant in turn before querying that tenant's
 * unpublished events. Every individual query this job runs is still fully
 * RLS-scoped to a single tenant, exactly like a normal request would be —
 * there's no special "trusted internal job" bypass to misuse or misconfigure.
 * The tradeoff is N queries instead of 1 for N tenants; at the scale this
 * job runs (a periodic sweep, not a hot path), that's the right tradeoff.
 */
@Component
public class MeteringPublishReconciler {

    private static final Logger log = LoggerFactory.getLogger(MeteringPublishReconciler.class);
    private static final int MAX_ATTEMPTS_BEFORE_ALERT = 5;

    private final JdbcTemplate jdbcTemplate;
    private final MeteringEventRepository repository;
    private final UsageEventPublisher publisher;

    public MeteringPublishReconciler(JdbcTemplate jdbcTemplate, MeteringEventRepository repository,
                                      UsageEventPublisher publisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 60_000) // every 60s; tune per real-world EventBridge SLOs
    public void reconcileUnpublishedEvents() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.SECONDS);
        // Give the normal AFTER_COMMIT path a head start — only chase down
        // events old enough that the fast path has plausibly already failed,
        // rather than racing it and double-publishing the common case.

        List<UUID> tenantIds = jdbcTemplate.queryForList("SELECT id FROM tenants", UUID.class);

        for (UUID tenantId : tenantIds) {
            TenantContext.setTenantId(tenantId.toString());
            try {
                reconcileForTenant(cutoff);
            } catch (Exception e) {
                log.error("Reconciliation failed for tenant {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void reconcileForTenant(Instant cutoff) {
        List<MeteringEvent> unpublished = repository.findUnpublishedOlderThan(cutoff);
        for (MeteringEvent event : unpublished) {
            try {
                publisher.publish(new MeteringEventRecorded(
                        event.getId(), event.getTenantId(), event.getEventType(), event.getQuantity()));
                event.setPublishedAt(Instant.now());
                repository.save(event);
            } catch (Exception e) {
                event.setPublishAttempts(event.getPublishAttempts() + 1);
                repository.save(event);
                if (event.getPublishAttempts() >= MAX_ATTEMPTS_BEFORE_ALERT) {
                    // Real deployment: page/alert here (e.g. emit a metric
                    // or call an incident-management webhook). Logging is
                    // the stand-in for this demo.
                    log.error("Metering event {} has failed to publish {} times — needs manual attention",
                            event.getId(), event.getPublishAttempts(), e);
                } else {
                    log.warn("Retry {} failed for metering event {}, will retry again next sweep",
                            event.getPublishAttempts(), event.getId(), e);
                }
            }
        }
    }
}
