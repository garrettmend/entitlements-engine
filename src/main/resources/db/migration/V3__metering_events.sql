-- Creates tenant-scoped metering events, idempotency constraints, publication indexes, and RLS policy.
-- Usage metering events: tenant-owned, so it follows the same RLS pattern
-- as `reports` from V2. The one new wrinkle is the idempotency constraint.

CREATE TABLE metering_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    idempotency_key  VARCHAR(255) NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    quantity         NUMERIC(18, 4) NOT NULL DEFAULT 1,
    metadata         JSONB,
    published_at     TIMESTAMPTZ,          -- NULL until confirmed sent to EventBridge
    publish_attempts INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The dedup key is the COMPOSITE (tenant_id, idempotency_key), not
    -- idempotency_key alone. If it were global, two different tenants'
    -- services independently generating the string "req-001" as their
    -- idempotency key (very plausible — many clients just use a counter or
    -- a request UUID scoped to their own system) would collide with each
    -- other despite having nothing to do with one another. Scoping the
    -- constraint to tenant_id makes collision only possible within a
    -- single tenant's own key space, which is the actual intent of
    -- idempotency keys.
    CONSTRAINT uq_metering_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_metering_events_tenant_id ON metering_events(tenant_id);

-- Used by the reconciliation job (MeteringPublishReconciler) to find events
-- that were durably written but never confirmed as published to EventBridge
-- — e.g. the process crashed between commit and the async publish attempt.
CREATE INDEX idx_metering_events_unpublished ON metering_events(created_at)
    WHERE published_at IS NULL;

ALTER TABLE metering_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE metering_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_metering_events ON metering_events
    USING (tenant_id::text = current_setting('app.current_tenant', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant', true));

-- NOTE on RLS + the unique constraint interacting: Postgres enforces UNIQUE
-- constraints via the underlying btree index, which is NOT filtered by RLS
-- policies — uniqueness is checked against all rows in the table regardless
-- of whether the current role could "see" them via a SELECT. This is
-- exactly why the composite (tenant_id, idempotency_key) key above is safe:
-- a conflict can only occur against another row with the SAME tenant_id,
-- and RLS's WITH CHECK already guarantees you can only ever insert rows
-- with your own tenant_id in the first place. If the constraint were on
-- idempotency_key alone, RLS would NOT have protected you from a spurious
-- cross-tenant conflict — the uniqueness check happens beneath RLS, not
-- filtered by it.
