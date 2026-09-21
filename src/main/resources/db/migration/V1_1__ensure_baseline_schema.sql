-- Repairs or completes the baseline tenant and report schema before Row-Level Security is enabled.
-- Repairs databases that were incorrectly baselined before V1 ran.
-- On a fresh database these statements are no-ops because V1 already created
-- the tables. This migration must remain before V2, which applies RLS to reports.

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