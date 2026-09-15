package com.platform.entitlements.metering;

import com.platform.entitlements.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static com.platform.entitlements.metering.MeteringDtos.MeteringEventResponse;
import static com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;

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

    @Transactional
    public MeteringEventResponse recordUsage(String idempotencyKey, RecordUsageRequest request) {
        UUID tenantId = UUID.fromString(TenantContext.getTenantId());

        Optional<UUID> insertedId = dao.insertIfAbsent(
                tenantId, idempotencyKey, request.eventType(), request.quantity());

        if (insertedId.isPresent()) {
            // We won the race: this is a genuinely new event. Fetch it back
            // (cheap — same transaction, same connection) so the response
            // reflects exactly what's now durably stored, then queue the
            // EventBridge publish for after this transaction commits.
            MeteringEvent event = repository.findById(insertedId.get())
                    .orElseThrow(() -> new IllegalStateException(
                            "Just-inserted metering event not found — should be unreachable"));

            applicationEventPublisher.publishEvent(new MeteringEventRecorded(
                    event.getId(), event.getTenantId(), event.getEventType(), event.getQuantity()));

            return MeteringEventResponse.of(event, false);
        }

        // We lost the race, or this is a plain retry arriving later: a row
        // with this (tenant_id, idempotency_key) already exists. RLS scopes
        // this lookup to the current tenant already, and the composite
        // unique constraint means it's this tenant's own prior event.
        MeteringEvent existing = repository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "insertIfAbsent reported a conflict but no existing row was found — " +
                        "should be unreachable unless the row was deleted between the two queries"));

        if (!existing.getEventType().equals(request.eventType())
                || existing.getQuantity().compareTo(request.quantity()) != 0) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key '" + idempotencyKey + "' was already used for a different request " +
                    "(eventType=" + existing.getEventType() + ", quantity=" + existing.getQuantity() + ")");
        }

        return MeteringEventResponse.of(existing, true);
    }
}
