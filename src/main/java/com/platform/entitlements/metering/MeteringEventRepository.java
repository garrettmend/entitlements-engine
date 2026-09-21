/** Provides JPA lookups and updates for existing and unpublished metering events. */
package com.platform.entitlements.metering;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WHY THIS FILE EXISTS:
 * This repository provides the read and update operations needed after a
 * metering event has been inserted by MeteringEventDao. The DAO owns the
 * atomic INSERT ... ON CONFLICT operation; this interface owns loading an
 * existing event, finding unpublished events for recovery, and standard JPA
 * persistence operations such as findById and save.
 *
 * RUNTIME FLOW:
 * 1. Spring Data creates the repository implementation at startup.
 * 2. MeteringService uses the idempotency-key lookup after an insert conflict.
 * 3. MeteringEventPublishListener and MeteringPublishReconciler use inherited
 *    lookups and the unpublished-event query to update delivery status.
 * 4. PostgreSQL RLS limits tenant-owned results to the current tenant context.
 */
public interface MeteringEventRepository extends JpaRepository<MeteringEvent, UUID> {

    // Step 1: use Spring Data's derived query for the duplicate-request path.
    // The generated lookup retrieves the existing event after MeteringEventDao
    // reports that the tenant/key pair already exists. RLS scopes the result
    // to the current tenant.
    Optional<MeteringEvent> findByIdempotencyKey(String idempotencyKey);

    // Step 2: find durable outbox rows that still need external publication.
    // JPQL uses entity properties, not database column names. The age cutoff
    // gives the normal asynchronous publisher time to run before recovery
    // attempts the same event.
    @Query("SELECT e FROM MeteringEvent e WHERE e.publishedAt IS NULL AND e.createdAt < :olderThan")
    List<MeteringEvent> findUnpublishedOlderThan(Instant olderThan);
}