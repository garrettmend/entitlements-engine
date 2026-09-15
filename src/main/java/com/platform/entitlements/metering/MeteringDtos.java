package com.platform.entitlements.metering;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class MeteringDtos {

    public record RecordUsageRequest(
            @NotBlank String eventType,
            @Positive BigDecimal quantity
    ) {
    }

    public record MeteringEventResponse(
            UUID id,
            String eventType,
            BigDecimal quantity,
            Instant createdAt,
            // True when this response describes an event that was already
            // recorded by an earlier call with the same Idempotency-Key —
            // i.e. this HTTP call did not create anything new. Callers that
            // care about "did my retry actually double-charge me" should
            // check this rather than just the HTTP status code.
            boolean replayed
    ) {
        static MeteringEventResponse of(MeteringEvent event, boolean replayed) {
            return new MeteringEventResponse(
                    event.getId(), event.getEventType(), event.getQuantity(),
                    event.getCreatedAt(), replayed);
        }
    }
}
