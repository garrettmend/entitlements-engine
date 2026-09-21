/** Contains the validated usage request and the response returned by the metering API. */
package com.platform.entitlements.metering;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// DTO = Data Transfer Object
// This outer class acts as a container for the incoming and outgoing 
// JSON payloads used by the MeteringController.
public class MeteringDtos {

    // 1. THE INCOMING REQUEST DTO
    // Java 'record' automatically creates an immutable object with auto-generated 
    // constructors, getters, equals(), hashCode(), and toString().
    public record RecordUsageRequest(
            // Validation: Prevents null, empty, or whitespace-only strings (e.g. "")
            @NotBlank String eventType,

            // Validation: Ensures quantity is strictly > 0 (prevents negative or zero billing)
            @Positive BigDecimal quantity
    ) {
    }

    // 2. THE OUTGOING RESPONSE DTO
    // Represents the exact JSON payload returned to the caller.
    public record MeteringEventResponse(
            UUID id,
            String eventType,
            BigDecimal quantity,
            Instant createdAt,

            // 3. THE REPLAY FLAG
            // True if this request was caught as a duplicate by an Idempotency-Key.
            // Tells the caller: "Here is your confirmation, but we did NOT double-charge you."
            boolean replayed
    ) {
        // 4. STATIC FACTORY METHOD
        // A helper function that takes an internal database entity (MeteringEvent) 
        // and converts it cleanly into this outward-facing API response object.
        static MeteringEventResponse of(MeteringEvent event, boolean replayed) {
            return new MeteringEventResponse(
                    event.getId(), event.getEventType(), event.getQuantity(),
                    event.getCreatedAt(), replayed);
        }
    }
}