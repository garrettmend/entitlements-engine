/** Coordinates atomic usage recording, replay detection, conflict handling, and after-commit event creation. */
package com.platform.entitlements.metering;

import com.platform.entitlements.metering.MeteringDtos.MeteringEventResponse;
import com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;
import com.platform.entitlements.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static com.platform.entitlements.metering.MeteringDtos.MeteringEventResponse;
import static com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;


//This is the central application-service class.

//Its recordUsage method is transactional and performs the following steps:

//Gets the current tenant from TenantContext.
//Attempts an atomic insert through MeteringEventDao.
//If insertion succeeds:
//Loads the inserted row.
//Publishes a Spring application event.
//Returns replayed=false.
//If insertion loses a uniqueness conflict:
//Loads the existing row.
//Compares the old and new payload.
//Returns replayed=true if they match.
//Throws IdempotencyKeyConflictException if they differ.
//The crucial distinction is between a retry and a conflict.


//The key identifies one logical operation. It cannot be reused to represent a different operation.

//The method publishes MeteringEventRecorded only for the newly inserted row. Replays do not publish again.

@Service
public class MeteringService {

    private final MeteringEventDao dao;
    private final MeteringEventRepository repository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public MeteringService(MeteringEventDao dao, MeteringEventRepository repository,
                           ApplicationEventPublisher applicationEventPublisher) {
        this.dao = dao;
        this.repository = repository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // 1. THE @TRANSACTIONAL SAFETY NET
    // Treats everything inside this method as a single, all-or-nothing operation.
    // If it crashes at any point, the database rolls back any partial changes.
    @Transactional
    public MeteringEventResponse recordUsage(String idempotencyKey, RecordUsageRequest request) {
        UUID tenantId = UUID.fromString(TenantContext.getTenantId());

        // 2. THE DATABASE "RACE"
        // Tries to save the record. The DB enforces one unique key per tenant.
        // Returns the new ID if successful, or 'empty' if the key already exists.
        Optional<UUID> insertedId = dao.insertIfAbsent(
                tenantId, idempotencyKey, request.eventType(), request.quantity());

        // 3. IF WE WIN (NEW EVENT)
        if (insertedId.isPresent()) {
            // 3a. Read it back: ensures we have the exact data/timestamp saved by the DB
            MeteringEvent event = repository.findById(insertedId.get())
                    .orElseThrow(() -> new IllegalStateException(
                            "Just-inserted metering event not found — should be unreachable"));

            // 3b. Broadcast the news: tells other parts of the app (like a billing module)
            // that a new event happened, but only after this transaction safely commits.
            applicationEventPublisher.publishEvent(new MeteringEventRecorded(
                    event.getId(), event.getTenantId(), event.getEventType(), event.getQuantity()));

            // 3c. Return the data, flagging 'false' because this is NOT a replay
            return MeteringEventResponse.of(event, false);
        }

        // 4. IF WE LOSE (THE REPLAY CHECK)
        // We fetch the existing record that already claimed this idempotency key.
        MeteringEvent existing = repository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "insertIfAbsent reported a conflict but no existing row was found — " +
                        "should be unreachable unless the row was deleted between the two queries"));

        // 4a. The Conflict Check: ensure the client isn't reusing an old receipt 
        // number for a completely different transaction.
        if (!existing.getEventType().equals(request.eventType())
                || existing.getQuantity().compareTo(request.quantity()) != 0) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key '" + idempotencyKey + "' was already used for a different request " +
                    "(eventType=" + existing.getEventType() + ", quantity=" + existing.getQuantity() + ")");
        }

        // 4b. Return the existing record, flagging 'true' because this IS a replay
        return MeteringEventResponse.of(existing, true);
    }
}