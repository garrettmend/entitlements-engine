/** Carries the durable metering event data from the transaction to the after-commit publisher. */
package com.platform.entitlements.metering;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by MeteringService immediately after MeteringEventDao's insert
 * succeeds within the request's transaction — but the actual EventBridge
 * publish only happens once that transaction COMMITS (see
 * MeteringEventPublishListener, which listens with phase = AFTER_COMMIT).
 *
 * Why not just call EventBridge directly inside MeteringService? Two reasons:
 * 
 * 1. THE CONNECTION POOL BOTTLENECK
 * EventBridge is a network call. Making it inside the DB transaction
 * holds a connection (and the tenant session variable on it) open for
 * the duration of that network call, and ties unrelated failure modes
 * together — a slow/unavailable EventBridge would make writes to
 * metering_events slow or fail too.
 * 
 * 2. THE PHANTOM EVENT PROBLEM (Rollbacks)
 * If something else in the transaction fails and rolls back AFTER we'd
 * already called EventBridge, we'd have "published" an event describing
 * a fact (the metering row) that no longer exists. AFTER_COMMIT
 * guarantees we only ever publish for writes that actually happened.
 */
// 3. THE IMMUTABLE DATA CARRIER
// A simple, read-only record that carries the facts of the event from the 
// MeteringService over to the Listener. It contains everything the external 
// billing system needs to know.
public record MeteringEventRecorded(UUID eventId, UUID tenantId, String eventType, BigDecimal quantity) {
}