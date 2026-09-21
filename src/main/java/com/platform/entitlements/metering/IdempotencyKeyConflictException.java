/** Signals that an idempotency key was reused with a different event payload. */
package com.platform.entitlements.metering;

/**
 * Thrown when a caller reuses an Idempotency-Key that's already associated
 * with a DIFFERENT event_type/quantity than what they just sent. This is
 * not a safe retry — it's either a client bug (reusing a key across
 * unrelated requests) or an attempt to smuggle a different outcome under
 * an old key. Real idempotency semantics (see Stripe's API design, which
 * this mirrors) treat this as a hard conflict, not a silent replay of
 * whichever request happened to arrive first.
 */
public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
