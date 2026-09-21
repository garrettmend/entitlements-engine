-- Creates the initial tenants and reports tables that form the application's baseline schema.
-- Baseline schema: tenants + one example protected resource (reports).
-- Every tenant-owned table in this system MUST have a NOT NULL tenant_id
-- column with an RLS policy applied in V2. Adding a new table without
-- doing so is the #1 way to accidentally reintroduce a cross-tenant leak.

CREATE TABLE tenants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,
    subscription_tier   VARCHAR(50)  NOT NULL DEFAULT 'FREE'
                            CHECK (subscription_tier IN ('FREE', 'PRO', 'ENTERPRISE')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Example tenant-owned resource. Stand-in for "any business entity your
-- SaaS actually manages" — swap for your real domain tables, but every one
-- of them follows this same shape: a tenant_id FK + the RLS policy in V2.
CREATE TABLE reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reports_tenant_id ON reports(tenant_id);
