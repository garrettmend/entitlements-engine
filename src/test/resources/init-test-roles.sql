-- Creates the restricted runtime PostgreSQL role used to prove Row-Level Security in integration tests.
-- Runs once during Postgres container initdb, as the container's default
-- superuser (see PostgreSQLContainer.withUsername in the test — that user
-- is ALWAYS a superuser in the official postgres image, which is exactly
-- why we don't want the application to ever connect as it).
--
-- This creates a separate, non-superuser role for the application's
-- runtime connection pool. Because it is neither a superuser NOR the owner
-- of the tables Flyway will create, Postgres RLS applies to it automatically
-- — no FORCE ROW LEVEL SECURITY workaround needed for this role specifically.
-- (We still keep FORCE in the migration as defense in depth in case someone
-- later points the runtime pool at the owning role by mistake.)

CREATE ROLE entitlements_app LOGIN PASSWORD 'app_runtime_password';

GRANT USAGE ON SCHEMA public TO entitlements_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO entitlements_app;

-- Tables don't exist yet at this point (Flyway hasn't run). ALTER DEFAULT
-- PRIVILEGES makes the grant apply retroactively to tables the superuser
-- creates LATER, so entitlements_app gets access to Flyway-created tables
-- without us needing to re-run GRANT after every migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO entitlements_app;
