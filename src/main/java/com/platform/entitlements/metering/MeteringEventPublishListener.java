package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Listens for MeteringEventRecorded with phase = AFTER_COMMIT, so this only
 * ever fires for events that are durably in the database — never for a
 * write that later rolled back.
 *
 * IMPORTANT: this is @Async, which means it runs on a DIFFERENT THREAD from
 * the original HTTP request. TenantContext is a ThreadLocal — it does NOT
 * carry over to the async thread automatically (that thread never passed
 * through TenantFilter). We have to explicitly set it here from the event's
 * payload before touching the repository, or MeteringEventRepository's
 * queries will either throw (TenantContext.getTenantId() fails loudly on
 * an unset thread) or, worse if that check were ever weakened, silently
 * run with no tenant scoping at all.
 */
@org.springframework.stereotype.Component
public class MeteringEventPublishListener {

    private static final Logger log = LoggerFactory.getLogger(MeteringEventPublishListener.class);

    private final UsageEventPublisher publisher;
    private final MeteringEventRepository repository;

    public MeteringEventPublishListener(UsageEventPublisher publisher, MeteringEventRepository repository) {
        this.publisher = publisher;
        this.repository = repository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMeteringEventRecorded(MeteringEventRecorded event) {
        TenantContext.setTenantId(event.tenantId().toString());
        try {
            publisher.publish(event);
            markPublished(event.eventId());
        } catch (Exception e) {
            // Deliberately swallowed, not rethrown: there's no caller left
            // to propagate this to (the original HTTP request already
            // returned a 201 before this async listener even started).
            // The reconciliation job (MeteringPublishReconciler) is what
            // actually guarantees eventual delivery — this just records
            // that an attempt was made and failed, so the reconciler knows
            // to try again and eventually alert if attempts keep climbing.
            log.warn("Failed to publish metering event {} to EventBridge, will be retried by reconciler",
                    event.eventId(), e);
            incrementAttempts(event.eventId());
        } finally {
            TenantContext.clear();
        }
    }

    private void markPublished(java.util.UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setPublishedAt(Instant.now());
            repository.save(e);
        });
    }

    private void incrementAttempts(java.util.UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setPublishAttempts(e.getPublishAttempts() + 1);
            repository.save(e);
        });
    }
}
