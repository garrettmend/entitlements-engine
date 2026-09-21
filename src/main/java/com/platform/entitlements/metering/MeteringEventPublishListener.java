/** Publishes committed metering events asynchronously and records success or failed-attempt metadata. */
package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

//This class acts as the asynchronous bridge between local database transactions and 
// cloud messaging (AWS EventBridge). It completes the fast-path half of the Transactional 
// Outbox Pattern by dispatching committed usage events on a background worker thread.

@org.springframework.stereotype.Component
public class MeteringEventPublishListener {

    private static final Logger log = LoggerFactory.getLogger(MeteringEventPublishListener.class);

    private final UsageEventPublisher publisher;
    private final MeteringEventRepository repository;

    public MeteringEventPublishListener(UsageEventPublisher publisher, MeteringEventRepository repository) {
        this.publisher = publisher;
        this.repository = repository;
    }

    // 1. ASYNCHRONOUS NON-BLOCKING EXECUTION
    // Offloads work to a Spring task executor thread pool. The caller's HTTP request thread
    // can commit its transaction and return 201 Created without waiting for network IO to AWS.
    @Async

    // 2. TRANSACTIONAL OUTBOX SAFETY GUARANTEE
    // Triggers ONLY after the database transaction has successfully COMMITTED.
    // If the database insert rolls back (e.g., constraint error or exception), 
    // this method never fires, preventing "phantom events" on EventBridge.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMeteringEventRecorded(MeteringEventRecorded event) {
        
        // 3. THREADLOCAL CONTEXT RE-HYDRATION
        // @Async shifts execution to a new thread from the pool. Because ThreadLocal state 
        // does not cross thread boundaries automatically, we must re-establish the tenant 
        // identity from the event payload before interacting with database repositories under RLS.
        TenantContext.setTenantId(event.tenantId().toString());
        
        try {
            // Attempt network delivery to EventBridge/Kinesis
            publisher.publish(event);
            
            // Mark event as successfully processed in local outbox store
            markPublished(event.eventId());
        } catch (Exception e) {
            // 4. FAULT TOLERANCE & DELEGATION TO RECONCILER
            // Exceptions thrown on @Async worker threads cannot propagate back to the caller.
            // We catch the failure, increment the attempt counter, and rely on 
            // MeteringPublishReconciler to retry publishing later.
            log.warn("Failed to publish metering event {} to EventBridge, will be retried by reconciler",
                    event.eventId(), e);
            incrementAttempts(event.eventId());
        } finally {
            // 5. PREVENT THREAD POOL POLLUTION
            // Threads in Spring's task pool are reused across requests.
            // Clearing TenantContext in a finally block guarantees no tenant leak 
            // occurs when this thread processes a subsequent task.
            TenantContext.clear();
        }
    }

    private void markPublished(java.util.UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setPublishedAt(Instant.now());
            repository.save(e); // Sets published_at timestamp in database
        });
    }

    private void incrementAttempts(java.util.UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setPublishAttempts(e.getPublishAttempts() + 1);
            repository.save(e); // Increments counter for backoff logic and ops alerts
        });
    }
}