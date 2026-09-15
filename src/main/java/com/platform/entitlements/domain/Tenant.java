package com.platform.entitlements.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenants is the registry table, not tenant-owned data — it has no RLS
 * policy (see V1__baseline_schema.sql). Any authenticated request can only
 * ever read its OWN tenant's row via TenantAdminController (which checks
 * the JWT's tenant claim against the path), but nothing at the database
 * layer prevents querying other rows the way RLS prevents it on `reports`.
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "subscription_tier", nullable = false)
    private String subscriptionTier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
