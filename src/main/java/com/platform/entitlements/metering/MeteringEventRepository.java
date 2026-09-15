package com.platform.entitlements.metering;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeteringEventRepository extends JpaRepository<MeteringEvent, UUID> {

    // Used on the duplicate-request path: RLS already scopes this to the
    // current tenant, but the composite unique constraint means this is
    // also always at most one row for the current tenant regardless.
    Optional<MeteringEvent> findByIdempotencyKey(String idempotencyKey);

    // Used by MeteringPublishReconciler. Note this method is called from a
    // background job, not a web request — see that class for how it still
    // respects RLS (by looping per-tenant) rather than bypassing it.
    @Query("SELECT e FROM MeteringEvent e WHERE e.publishedAt IS NULL AND e.createdAt < :olderThan")
    List<MeteringEvent> findUnpublishedOlderThan(Instant olderThan);
}
