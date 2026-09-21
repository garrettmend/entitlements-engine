/** Unit-tests metering service branches for new events, safe replays, and payload conflicts. */
package com.platform.entitlements.metering;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.platform.entitlements.tenant.TenantContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.platform.entitlements.metering.MeteringDtos.MeteringEventResponse;
import static com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito test of the branching logic in MeteringService. The
 * ON CONFLICT race-safety itself is NOT provable with mocks — that's
 * covered separately by MeteringConcurrencyIT against real Postgres. This
 * test exists to check MeteringService does the right thing given each of
 * the three outcomes MeteringEventDao.insertIfAbsent can produce.
 */
class MeteringServiceTest {

    private final MeteringEventDao dao = mock(MeteringEventDao.class);
    private final MeteringEventRepository repository = mock(MeteringEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MeteringService service = new MeteringService(dao, repository, eventPublisher);

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Test
    void newEventIsInsertedAndPublishedAndMarkedNotReplayed() {
        TenantContext.setTenantId(TENANT_ID.toString());
        try {
            UUID newId = UUID.randomUUID();
            MeteringEvent stored = eventEntity(newId, "API_CALL", new BigDecimal("1"));

            when(dao.insertIfAbsent(eq(TENANT_ID), eq("key-1"), eq("API_CALL"), eq(new BigDecimal("1"))))
                    .thenReturn(Optional.of(newId));
            when(repository.findById(newId)).thenReturn(Optional.of(stored));

            MeteringEventResponse response = service.recordUsage(
                    "key-1", new RecordUsageRequest("API_CALL", new BigDecimal("1")));

            assertThat(response.replayed()).isFalse();
            assertThat(response.id()).isEqualTo(newId);

            ArgumentCaptor<MeteringEventRecorded> captor = ArgumentCaptor.forClass(MeteringEventRecorded.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().eventId()).isEqualTo(newId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void exactDuplicateReturnsExistingRowAndDoesNotPublishAgain() {
        TenantContext.setTenantId(TENANT_ID.toString());
        try {
            UUID existingId = UUID.randomUUID();
            MeteringEvent existing = eventEntity(existingId, "API_CALL", new BigDecimal("1"));

            when(dao.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            MeteringEventResponse response = service.recordUsage(
                    "key-1", new RecordUsageRequest("API_CALL", new BigDecimal("1")));

            assertThat(response.replayed()).isTrue();
            assertThat(response.id()).isEqualTo(existingId);
            verifyNoInteractions(eventPublisher); // must NOT re-publish on a replay
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void sameKeyDifferentPayloadIsRejectedAsConflict() {
        TenantContext.setTenantId(TENANT_ID.toString());
        try {
            MeteringEvent existing = eventEntity(UUID.randomUUID(), "API_CALL", new BigDecimal("1"));

            when(dao.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            // Same key, but a DIFFERENT quantity than what was stored — a
            // safe idempotent replay would never have a different payload.
            assertThatThrownBy(() -> service.recordUsage(
                    "key-1", new RecordUsageRequest("API_CALL", new BigDecimal("999"))))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        } finally {
            TenantContext.clear();
        }
    }

    private MeteringEvent eventEntity(UUID id, String eventType, BigDecimal quantity) {
        MeteringEvent e = new MeteringEvent();
        e.setId(id);
        e.setTenantId(TENANT_ID);
        e.setIdempotencyKey("key-1");
        e.setEventType(eventType);
        e.setQuantity(quantity);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
