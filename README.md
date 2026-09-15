# Entitlements Engine — Part 1: Multi-Tenant Isolation (RLS)

## What's built so far

- Postgres Row-Level Security enforcing tenant isolation at the database engine level
- Two-role setup: `entitlements_owner` (schema owner, runs migrations) and
  `entitlements_app` (non-superuser, non-owner — the role your app actually
  queries as, so RLS applies to it with no bypass)
- `TenantFilter` — pulls `tenant_id` out of the validated Cognito JWT after
  Spring Security authenticates the request
- `TenantAwareDataSource` — sets/resets the Postgres session variable per
  connection checkout from the Hikari pool
- `ReportRepository` / `ReportController` — a sample tenant-owned resource
  with **zero manual tenant filtering in the query code**, proving RLS does
  the work
- `TenantIsolationIT` — a Testcontainers integration test against real
  Postgres (not H2, since H2 doesn't implement RLS) that proves cross-tenant
  isolation and the fail-closed behavior when no tenant is set

## Local setup

```bash
# 1. Start Postgres
docker run -d --name entitlements-db -p 5432:5432 \
  -e POSTGRES_USER=entitlements_owner \
  -e POSTGRES_PASSWORD=owner_password \
  -e POSTGRES_DB=entitlements \
  postgres:16-alpine

# 2. Create the restricted runtime role (owner role above is a superuser,
#    which must never be the role the app queries through)
docker exec -it entitlements-db psql -U entitlements_owner -d entitlements -c "
  CREATE ROLE entitlements_app LOGIN PASSWORD 'app_runtime_password';
  GRANT USAGE ON SCHEMA public TO entitlements_app;
  ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO entitlements_app;
"

# 3. Set a real Cognito issuer, or stub one out for local testing
export COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>

# 4. Run it
mvn spring-boot:run
```

## Running the tests

```bash
mvn test
```

`TenantIsolationIT` spins up its own throwaway Postgres via Testcontainers
(Docker must be running) and creates its own roles — it doesn't touch the
`docker run` container above.

## Railway deployment

Railway detects the included `Dockerfile` and `railway.toml`. Add a PostgreSQL
service to the project, then configure these variables on the application
service:

```text
DB_HOST=${{Postgres.PGHOST}}
DB_PORT=${{Postgres.PGPORT}}
DB_NAME=${{Postgres.PGDATABASE}}
DB_APP_USER=${{Postgres.PGUSER}}
DB_APP_PASSWORD=${{Postgres.PGPASSWORD}}
MIGRATION_DB_USER=${{Postgres.PGUSER}}
MIGRATION_DB_PASSWORD=${{Postgres.PGPASSWORD}}
COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
AWS_REGION=<aws-region>
ENTITLEMENTS_TABLE=<dynamodb-table-name>
METERING_EVENT_BUS=<eventbridge-bus-name>
```

Railway supplies `PORT` automatically. The migration and runtime database
credentials may be the same Railway PostgreSQL role because the migrations
enable `FORCE ROW LEVEL SECURITY`; use separate roles when your database
provisioning supports them. AWS credentials must also be available to the
service through Railway environment variables or an attached AWS identity.

## What I haven't verified for you

I built this without network access, so I couldn't actually run `mvn test`
or `mvn spring-boot:run` myself. Before you trust this:

1. Run `mvn test` first — `TenantIsolationIT` is the one that matters. If it
   passes, the core isolation guarantee is real, not just well-commented.
2. Double check the Hibernate `@GeneratedValue` default UUID strategy on
   `Report.id` matches what you expect — Hibernate 6 generates these
   client-side by default, no DB sequence needed, but worth confirming
   against your Hibernate version.
3. If you're on Spring Boot/Hibernate versions other than what's pinned in
   `pom.xml` (3.3.4), re-check `@TenantId`-adjacent APIs — this project
   deliberately avoids `@TenantId` in favor of RLS, but Hibernate's tenant
   APIs have shifted across 6.x minor versions.

## Next up

- **Part 3: ABAC entitlements** — `@PreAuthorize` + DynamoDB-backed
  feature/tier checks

---

# Part 2: Idempotent Usage Metering

## What's built

- `POST /metering/events` — internal endpoint for other services to report
  tenant usage, protected by an `Idempotency-Key` header
- Atomic dedup via `INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO
  NOTHING RETURNING id` (`MeteringEventDao`) — not a "check then insert",
  which has a race window that duplicate-request retries would actually hit
- A composite `(tenant_id, idempotency_key)` unique constraint, not a
  global one — see the comment in `V3__metering_events.sql` for why RLS
  alone doesn't protect you from a cross-tenant key collision if the
  constraint were scoped wrong
- Conflict detection: reusing a key with a *different* request body returns
  `409`, not a silent (and wrong) replay of the original response
- EventBridge publish happens **after** the DB transaction commits
  (`MeteringEventPublishListener`, `@TransactionalEventListener(phase =
  AFTER_COMMIT)`), not inside it
- `MeteringPublishReconciler` — a scheduled sweep that retries publishing
  any event that's been sitting unpublished for >30s, covering the crash
  window between "transaction committed" and "async publish actually ran"

## Two tests, testing two different things

- `MeteringServiceTest` — plain Mockito unit test of the three branches
  (new event / clean duplicate / conflicting duplicate). Fast, no Docker.
- `MeteringConcurrencyIT` — fires 20 genuinely concurrent threads at the
  same idempotency key against real Postgres (via a `CountDownLatch` to
  force them to overlap) and asserts exactly one wins. This is the test
  that actually proves the race-safety claim — the unit test above cannot,
  since Mockito doesn't have real concurrent database semantics.

## What I haven't verified for you

Same caveat as Part 1 — no network access in my sandbox, so I couldn't run
these. Before trusting this:

1. Run `mvn test` — pay particular attention to `MeteringConcurrencyIT`.
   Thread-timing tests are inherently a little more fragile than others; if
   it's flaky, the `readyLatch`/`startLatch` synchronization is the first
   place to look, not necessarily the production code.
2. `EventBridgeClient.builder().region(...).build()` resolves AWS
   credentials lazily (at first `putEvents` call), so the app should start
   fine locally with no AWS credentials configured — but you'll only find
   out publish actually works once you point it at a real event bus and
   trigger a metering call.
3. The reconciler's `@Scheduled(fixedDelay = 60_000)` and the 30-second
   cutoff are placeholder values — tune both against your actual EventBridge
   latency/error-rate SLOs before relying on them.

---

# Part 3: ABAC Entitlements

## What's built

- A custom `MethodSecurityExpressionHandler` (`EntitlementsMethodSecurityExpressionHandler`
  + `EntitlementsExpressionRoot`) exposing `tenantId` as a first-class SpEL
  variable, extracted from the JWT — lets `@PreAuthorize` reference it
  without adding a `tenantId` parameter to every protected method
- `EntitlementsService` (`@Component("entitlements")`) — the bean
  `@PreAuthorize("@entitlements.canAccess(tenantId, 'CREATE_REPORT')")`
  actually calls
- Two independent ABAC attributes, combinable per permission:
  subscription tier (tenant-level, cached in DynamoDB with Postgres
  fallback) and Cognito group membership (user-level, read straight off
  the JWT, no extra I/O)
- `PermissionRule` — a small static registry mapping permission codes to
  their tier/group requirements, matching the blueprint's own example:
  `CREATE_REPORT` needs Enterprise tier *and* the `REPORT_WRITER` group;
  `EXPORT_DATA` needs Pro-or-above tier and no group at all
- Write-through cache invalidation (`TenantAdminService`) — a tier change
  updates the cache immediately rather than waiting on TTL, answering the
  "what happens on a mid-session downgrade" question directly

## An honest tradeoff, called out in code

`EntitlementsExpressionRoot`'s javadoc says this plainly: building a whole
custom `MethodSecurityExpressionHandler` is more machinery than just having
`EntitlementsService.canAccess(permission)` read the tenant id from
`TenantContext` internally (it's already sitting there, populated by
`TenantFilter`, by the time any `@PreAuthorize` check runs). For one
permission check per method, the simpler version is genuinely better. The
custom expression root earns its cost once you want several derived
attributes available as first-class SpEL variables across a growing ABAC
surface. I built the more complex version because it's specifically what
the project brief asked to demonstrate — worth knowing you have a choice
here, not just copying the more impressive-looking option by default.

## Tests

- `EntitlementsServiceTest` — pure Mockito unit test of the tier/group
  boolean logic (six branches: tier-only pass/fail, combined pass/fail
  each direction, cache-miss-falls-back-to-Postgres, unknown permission
  code throws rather than silently denying or allowing)
- `ReportsAbacIT` — the test that actually matters here. Full `MockMvc`
  requests with real JWTs (via `SecurityMockMvcRequestPostProcessors.jwt()`)
  against a real Postgres, proving the entire chain works end to end:
  JWT → custom SpEL root → `canAccess()` → tier lookup → allow/deny. Covers
  the same four scenarios as the blueprint's own example (Enterprise+group
  succeeds, Enterprise-without-group fails, Pro-with-group still fails,
  a tier-only rule succeeds independent of group)

## What I haven't verified for you

1. Run `mvn test` — `ReportsAbacIT` is the one to watch closely; it's the
   only test in this repo that exercises the custom `MethodSecurityExpressionHandler`
   for real rather than just asserting the boolean logic around it.
2. `DynamoDbTenantEntitlementsCache` is `@Profile("!test")` and untested by
   anything in this repo — the tests all run under the `test` profile
   against `InMemoryTenantEntitlementsCache` instead. Before trusting the
   real DynamoDB path, test it against an actual table (or DynamoDB Local)
   at least once.
3. `PermissionRule`'s table is static/in-code, as noted in its own javadoc.
   If you move it to a DB-backed table later, keep `EntitlementsService`'s
   `canAccess()` signature the same — the calling code (all your
   `@PreAuthorize` annotations) shouldn't need to change.

## A note on how this session went

While building Part 3, unexpected files and even edits to files I'd
already written kept appearing in the working directory — first new SQL
migrations, then a whole extra Java package, then modifications spliced
into `ReportController`, `AwsConfig`, and `SecurityConfig` that I hadn't
made. Each time, I stopped, flagged it, and rewrote the affected code from
scratch rather than building on top of anything I couldn't verify myself.
Worth knowing if you're reviewing this project closely — everything in
this final package is code I wrote and can account for line by line, but
the session itself surfaced something worth investigating on your end.
