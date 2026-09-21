-- Repairs missing tenant, report, and metering schema objects while preserving their indexes and RLS policies.
-- Repairs databases whose Flyway history records V3 but whose table is missing.

CREATE TABLE IF NOT EXISTS tenants (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255) NOT NULL,
    subscription_tier VARCHAR(50) NOT NULL DEFAULT 'FREE'
                          CHECK (subscription_tier IN ('FREE', 'PRO', 'ENTERPRISE')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reports (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    title      VARCHAR(255) NOT NULL,
    body       TEXT,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reports_tenant_id ON reports(tenant_id);

CREATE TABLE IF NOT EXISTS metering_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    idempotency_key  VARCHAR(255) NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    quantity         NUMERIC(18, 4) NOT NULL DEFAULT 1,
    metadata         JSONB,
    published_at     TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_metering_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_metering_events_tenant_id
    ON metering_events(tenant_id);

CREATE INDEX IF NOT EXISTS idx_metering_events_unpublished
    ON metering_events(created_at)
    WHERE published_at IS NULL;

ALTER TABLE metering_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE metering_events FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'metering_events'
          AND policyname = 'tenant_isolation_metering_events'
    ) THEN
        CREATE POLICY tenant_isolation_metering_events ON metering_events
            USING (tenant_id::text = current_setting('app.current_tenant', true))
            WITH CHECK (tenant_id::text = current_setting('app.current_tenant', true));
    END IF;
END
$$;