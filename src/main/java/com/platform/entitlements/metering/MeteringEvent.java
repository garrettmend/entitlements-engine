package com.platform.entitlements.metering;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side representation of a metering event row. Rows are actually
 * INSERTed via MeteringEventDao (raw JDBC, so we can use
 * INSERT ... ON CONFLICT ... RETURNING — not expressible through a plain
 * JPA save()). This entity exists so we can look up an existing row
 * (on the duplicate-request path) and so the reconciliation job can query
 * and update `published_at` through normal JPA.
 */
@Entity
@Table(name = "metering_events")
@Getter
@Setter
@NoArgsConstructor
public class MeteringEvent {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private BigDecimal quantity;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
