-- Row-Level Security enforcement.
--
-- CRITICAL GOTCHA #1: RLS policies are silently bypassed for the table
-- OWNER and for superusers, regardless of what the policy says. If your
-- application connects as the same role that ran these migrations (often
-- the table owner), RLS will appear to do nothing and every tenant will
-- see every row. Two ways to fix this:
--   (a) FORCE ROW LEVEL SECURITY (used below) makes the policy apply even
--       to the table owner — simplest for a single-role setup like this demo.
--   (b) In production, prefer a dedicated non-owner `app_runtime` role for
--       the application's connection pool, distinct from the migration/DDL
--       role, so the owner bypass is never even in play.
-- Do at least (a). Ideally do both.
--
-- CRITICAL GOTCHA #2: the session variable must exist before it's read, or
-- current_setting() throws. We use the two-argument form
-- current_setting('app.current_tenant', true) — the `true` means "return
-- NULL instead of erroring if unset" — so a connection with no tenant set
-- yet (e.g. a pooled connection before our DataSource wrapper runs SET)
-- fails CLOSED (matches nothing) instead of throwing a 500.

ALTER TABLE reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE reports FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_reports ON reports
    USING (tenant_id::text = current_setting('app.current_tenant', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant', true));

-- USING governs which existing rows are visible to SELECT/UPDATE/DELETE.
-- WITH CHECK governs which rows can be INSERTED/UPDATED *into* — without
-- this, a bug that lets tenant_id be set incorrectly on insert would still
-- succeed and silently write into another tenant's namespace.

-- Repeat this exact pattern for every future tenant-owned table:
--
-- ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE <table> FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation_<table> ON <table>
--     USING (tenant_id::text = current_setting('app.current_tenant', true))
--     WITH CHECK (tenant_id::text = current_setting('app.current_tenant', true));
