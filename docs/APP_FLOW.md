# Entitlements Engine: End-to-End Application Flow

This document traces the application from process startup through the browser or service caller, request filtering, tenant isolation, business logic, persistence, external integrations, and response. Every flow box identifies the owning class and method, why that method is called, and what it does.

## 1. Startup: process to ready application

```mermaid
flowchart TD
    A["EntitlementsEngineApplication.main(String[] args)<br/><b>Why:</b> JVM entry point.<br/><b>Does:</b> Calls SpringApplication.run to create the application context and embedded server."]
    B["SpringApplication.run(EntitlementsEngineApplication.class, args)<br/><b>Why:</b> Bootstraps Spring Boot.<br/><b>Does:</b> Scans components, creates configuration beans, starts the web server, and runs bean init methods."]
    C["FlywayConfig.flyway(FlywayProperties)<br/><b>Why:</b> Schema must exist before repositories serve traffic.<br/><b>Does:</b> Repairs schema-history defaults and builds Flyway with the privileged migration URL/user/password."]
    D["Flyway.migrate()<br/><b>Why:</b> Bean initMethod runs after Flyway is built.<br/><b>Does:</b> Applies V1 through V4, including tenants, reports, metering_events, indexes, and PostgreSQL RLS policies."]
    E["DataSourceConfig.dataSourceProperties()<br/><b>Why:</b> Bind spring.datasource settings without creating the final bean yet.<br/><b>Does:</b> Provides runtime pool properties."]
    F["DataSourceConfig.hikariDataSource(DataSourceProperties)<br/><b>Why:</b> Build the physical connection pool.<br/><b>Does:</b> Creates HikariDataSource using the non-owner runtime database role."]
    G["DataSourceConfig.dataSource(HikariDataSource)<br/><b>Why:</b> All runtime persistence must carry tenant state to PostgreSQL.<br/><b>Does:</b> Wraps Hikari in TenantAwareDataSource and marks it primary."]
    H["AwsConfig.eventBridgeClient(String)<br/><b>Why:</b> Production metering needs an EventBridge transport.<br/><b>Does:</b> Creates an AWS SDK client using region and default credentials."]
    I["AwsConfig.dynamoDbEnhancedClient(String)<br/><b>Why:</b> Production ABAC needs a tenant-tier cache.<br/><b>Does:</b> Creates the DynamoDB enhanced client used by the cache bean."]
    J["SecurityConfig.publicUiFilterChain(HttpSecurity)<br/><b>Why:</b> The static UI must load without a tenant header.<br/><b>Does:</b> Permits / and /index.html."]
    K["SecurityConfig.filterChain(HttpSecurity)<br/><b>Why:</b> Configure API request processing.<br/><b>Does:</b> Enables CORS, disables CSRF for stateless bearer calls, permits health endpoints, and inserts TenantFilter."]
    L["MethodSecurityConfig.methodSecurityExpressionHandler()<br/><b>Why:</b> @PreAuthorize needs tenant-aware ABAC variables.<br/><b>Does:</b> Registers EntitlementsMethodSecurityExpressionHandler with method security enabled."]
    M["AwsConfig @EnableAsync + @EnableScheduling<br/><b>Why:</b> Metering has background publication and recovery paths.<br/><b>Does:</b> Enables @Async listener execution and the 60-second reconciler schedule."]
    N["Spring Boot ready<br/><b>Why:</b> Startup prerequisites are complete.<br/><b>Does:</b> Serves the embedded static UI and HTTP API."]

    A --> B
    B --> C --> D
    B --> E --> F --> G
    B --> H
    B --> I
    B --> J
    B --> K
    B --> L
    B --> M
    D --> N
    G --> N
    J --> N
    K --> N
    L --> N
    M --> N
```

**Important startup boundary:** `EntitlementsEngineApplication` excludes Spring Boot's automatic Flyway configuration because `FlywayConfig` deliberately runs migrations through a separate privileged connection. Runtime JPA/JDBC uses the restricted `entitlements_app` pool wrapped by `TenantAwareDataSource`.

## 2. Browser or service request: common guardrails

```mermaid
flowchart TD
    A["Browser index.html event handler or internal service HTTP client<br/><b>Why:</b> A user or platform service initiates an operation.<br/><b>Does:</b> Sends endpoint, JSON, bearer token when applicable, X-Tenant-Id, and sometimes Idempotency-Key."]
    B["index.html.apiCall(path, options, token)<br/><b>Why:</b> Centralize browser API calls.<br/><b>Does:</b> Adds X-Tenant-Id and X-User-Groups, calls fetch, parses JSON/text, and surfaces network/CORS errors."]
    C["SecurityConfig.corsConfigurationSource()<br/><b>Why:</b> Browser JavaScript is hosted separately from the API in deployment.<br/><b>Does:</b> Allows configured API methods/headers for stateless, non-cookie requests."]
    D["SecurityConfig.filterChain(HttpSecurity)<br/><b>Why:</b> Select the API security chain.<br/><b>Does:</b> Runs a stateless filter chain and places TenantFilter before anonymous authentication."]
    E["TenantFilter.shouldNotFilter(HttpServletRequest)<br/><b>Why:</b> Public UI and health calls have no tenant context.<br/><b>Does:</b> Skips filtering for /, /index.html, /actuator/**, and /health/**."]
    F["TenantFilter.doFilterInternal(request, response, filterChain)<br/><b>Why:</b> Protected data access needs an explicit tenant for the whole request.<br/><b>Does:</b> Reads X-Tenant-Id, sets TenantContext, rejects missing headers with 400, invokes the chain, and always clears context in finally."]
    G["TenantContext.setTenantId(String) / getTenantId() / clear()<br/><b>Why:</b> Carry tenant identity across controller, security, and repository calls on one thread.<br/><b>Does:</b> Validates and stores the ID in ThreadLocal, fails if absent, and removes it before thread reuse."]
    H["Spring Security JWT authentication<br/><b>Why:</b> Protected API calls may carry a Cognito bearer token.<br/><b>Does:</b> Resource-server security verifies the token and exposes its claims/groups to method security."]
    I["Controller method dispatch<br/><b>Why:</b> Spring MVC maps the HTTP verb/path to application behavior.<br/><b>Does:</b> Routes to ReportController, MeteringController, or TenantAdminController."]
    X["HTTP 400<br/><b>Why:</b> Protected request omitted X-Tenant-Id.<br/><b>Does:</b> Stops before controller and clears any request state."]
    Y["Static UI / health response<br/><b>Why:</b> Endpoint is public.<br/><b>Does:</b> Returns without tenant setup."]

    A --> B --> C --> D --> E
    E -->|public path| Y
    E -->|protected path| F
    F -->|missing X-Tenant-Id| X
    F -->|header present| G --> H --> I
```

`X-Tenant-Id` is the request-context input used by this repository. The browser decodes JWT claims only for display; the backend is responsible for real token verification. Internal admin and metering endpoints are currently permitted by the HTTP authorization rules and are documented in code as deployment/authentication responsibilities.

## 3. Tenant isolation at the database boundary

```mermaid
flowchart TD
    A["TenantAwareDataSource.getConnection()<br/><b>Why:</b> JPA/JDBC is borrowing a pooled connection.<br/><b>Does:</b> Gets a physical Hikari connection, applies the current tenant, and returns a proxy."]
    B["TenantAwareDataSource.applyTenant(Connection)<br/><b>Why:</b> PostgreSQL RLS needs a session value, not just an application variable.<br/><b>Does:</b> Sets app.current_tenant to TenantContext.getTenantId(), or empty when no tenant exists."]
    C["PostgreSQL RLS policy from V2/V4 migrations<br/><b>Why:</b> Enforce isolation even when repository code has no tenant WHERE clause.<br/><b>Does:</b> Allows rows only when tenant_id equals current_setting('app.current_tenant', true), including WITH CHECK on writes."]
    D["ReportRepository / TenantRepository / MeteringEventRepository SQL<br/><b>Why:</b> Controllers and services need persisted data.<br/><b>Does:</b> Executes normal JPA or JDBC queries; reports and metering rows are filtered by RLS."]
    E["TenantAwareDataSource.wrapWithResetOnClose(Connection)<br/><b>Why:</b> Pooled physical connections outlive one request.<br/><b>Does:</b> Resets app.current_tenant to empty before returning the connection to Hikari."]
    F["Fail closed<br/><b>Why:</b> Missing or stale tenant state must not expose another tenant.<br/><b>Does:</b> Empty session tenant matches no tenant-owned row; cross-tenant lookup returns empty/zero rows and mismatched inserts are rejected."]
    A --> B --> C --> D --> E --> F
```

## 4. Reports and ABAC flow

```mermaid
flowchart TD
    A["ReportController.listReports()<br/><b>Why:</b> GET /reports lists the caller's reports.<br/><b>Does:</b> Calls ReportRepository.findAll with no tenant predicate; PostgreSQL RLS supplies the tenant boundary."]
    B["ReportController.getReport(UUID id)<br/><b>Why:</b> GET /reports/{id} retrieves one report.<br/><b>Does:</b> Calls findById and returns 200 or 404; RLS makes another tenant's ID look absent."]
    C["ReportController.exportReports()<br/><b>Why:</b> GET /reports/export returns tenant data as CSV.<br/><b>Does:</b> Applies EXPORT_DATA authorization, loads RLS-filtered reports, and builds text/csv."]
    D["ReportController.createReport(CreateReportRequest, Authentication)<br/><b>Why:</b> POST /reports creates a report.<br/><b>Does:</b> Gets tenant from TenantContext, copies title/body, derives creator from authentication, and saves the entity."]
    E["MethodSecurity interceptor + EntitlementsMethodSecurityExpressionHandler.createSecurityExpressionRoot(Authentication, MethodInvocation)<br/><b>Why:</b> @PreAuthorize runs before create/export methods.<br/><b>Does:</b> Builds EntitlementsExpressionRoot with the current tenant and evaluates the SpEL expression."]
    F["EntitlementsExpressionRoot(Authentication, tenantId)<br/><b>Why:</b> Expressions need tenantId as a first-class property.<br/><b>Does:</b> Exposes the current tenant to @entitlements.canAccess(...)."]
    G["EntitlementsService.canAccess(String tenantId, String permissionCode)<br/><b>Why:</b> Decide whether the requested capability is allowed.<br/><b>Does:</b> Loads the static rule, checks minimum tenant tier, checks required user group, and ANDs all required attributes."]
    H["PermissionRule.forCode(String)<br/><b>Why:</b> Resolve the policy named by the annotation.<br/><b>Does:</b> Maps EXPORT_DATA to PRO+ and CREATE_REPORT to ENTERPRISE + REPORT_WRITER; unknown codes fail loudly."]
    I["EntitlementsService.resolveTier(UUID tenantId)<br/><b>Why:</b> Tier is a tenant attribute needed by ABAC.<br/><b>Does:</b> Reads TenantEntitlementsCache first; on miss reads TenantRepository.findById, converts the tier, and repopulates cache."]
    J["DynamoDbTenantEntitlementsCache.get(UUID) / put(UUID, SubscriptionTier)<br/><b>Why:</b> Avoid a Postgres tier lookup on every authorized request.<br/><b>Does:</b> Reads/writes a five-minute DynamoDB cache entry and treats SDK failure as a cache miss."]
    K["EntitlementsService.currentUserGroups()<br/><b>Why:</b> CREATE_REPORT has a user-level group requirement.<br/><b>Does:</b> Reads cognito:groups from the verified Jwt, with the X-User-Groups header as the fallback path used by this demo."]
    L["ReportRepository.findAll() / findById(UUID) / save(Report)<br/><b>Why:</b> Persist or retrieve reports.<br/><b>Does:</b> Uses Hibernate against the tenant-aware DataSource; RLS filters reads and validates tenant_id on writes."]
    M["HTTP 403 or successful controller invocation<br/><b>Why:</b> ABAC result controls method execution.<br/><b>Does:</b> Denies when tier/group requirements fail; otherwise continues to repository logic and returns 200/201."]

    A --> L
    B --> L
    C --> E
    D --> E
    E --> F --> G --> H
    G --> I --> J
    G --> K
    H --> M
    I --> M
    K --> M
    M -->|allowed for export/create| C
    M -->|allowed for create| D
    M -->|denied| N["Spring Security access denied response<br/><b>Why:</b> A @PreAuthorize rule returned false.<br/><b>Does:</b> Stops controller execution and returns 403."]
```

The list and single-report read paths do not use `@PreAuthorize`; they still require a tenant header and remain isolated by the database session variable and RLS. The create path requires both tenant tier and user group. Export requires tier only.

## 5. Metering flow: new event, replay, conflict, and response

```mermaid
flowchart TD
    A["index.html.sendMeteringEvent(keyOverride) or internal service POST /metering/events<br/><b>Why:</b> Record usage from the control plane or another platform service.<br/><b>Does:</b> Sends eventType, positive quantity, X-Tenant-Id, and Idempotency-Key."]
    B["MeteringController.recordUsage(String idempotencyKey, RecordUsageRequest request)<br/><b>Why:</b> Validate and expose the metering endpoint.<br/><b>Does:</b> Rejects blank keys, delegates to the service, and returns 201 for new or 200 for replay."]
    C["MeteringService.recordUsage(String, RecordUsageRequest)<br/><b>Why:</b> Coordinate the atomic write and idempotency decision in one transaction.<br/><b>Does:</b> Gets tenant ID, attempts insert, branches on inserted ID, and publishes only new-event notifications."]
    D["MeteringEventDao.insertIfAbsent(UUID, String, String, BigDecimal)<br/><b>Why:</b> Close the concurrent duplicate-request race.<br/><b>Does:</b> Executes INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id."]
    E["MeteringEventRepository.findById(UUID)<br/><b>Why:</b> Read the exact committed entity after a successful insert.<br/><b>Does:</b> Supplies response fields and the event notification payload."]
    F["ApplicationEventPublisher.publishEvent(MeteringEventRecorded)<br/><b>Why:</b> Defer external delivery until the database transaction commits.<br/><b>Does:</b> Queues an immutable event for the AFTER_COMMIT listener."]
    G["MeteringDtos.MeteringEventResponse.of(MeteringEvent, false)<br/><b>Why:</b> Shape a new-event API response.<br/><b>Does:</b> Returns ID/type/quantity/createdAt with replayed=false."]
    H["MeteringEventRepository.findByIdempotencyKey(String)<br/><b>Why:</b> Identify the row that won a concurrent or repeated key race.<br/><b>Does:</b> Loads the existing tenant-scoped event."]
    I["Payload comparison in MeteringService.recordUsage<br/><b>Why:</b> Distinguish a safe retry from key misuse.<br/><b>Does:</b> Same eventType and quantity returns replayed=true; any difference throws IdempotencyKeyConflictException."]
    J["MeteringController.handleConflict(IdempotencyKeyConflictException)<br/><b>Why:</b> Convert key misuse into an API result.<br/><b>Does:</b> Returns HTTP 409 with the conflict message."]
    K["MeteringDtos.MeteringEventResponse.of(MeteringEvent, true)<br/><b>Why:</b> Shape a replay response.<br/><b>Does:</b> Returns the original row and replayed=true without publishing another event."]
    L["HTTP response<br/><b>Why:</b> Caller needs an explicit outcome.<br/><b>Does:</b> Returns 201 new, 200 replay, 400 blank key, or 409 payload conflict."]

    A --> B --> C --> D
    D -->|inserted ID present| E --> F --> G --> L
    D -->|Optional.empty: key already exists| H --> I
    I -->|same payload| K --> L
    I -->|different payload| J --> L
```

`MeteringEvent` is the JPA read/update model, while `MeteringEventDao` owns insertion because the atomic `ON CONFLICT ... RETURNING` SQL is the concurrency guarantee. The database unique constraint is the authority when multiple requests arrive together.

## 6. Metering after-commit delivery and recovery

```mermaid
flowchart TD
    A["MeteringEventPublishListener.onMeteringEventRecorded(MeteringEventRecorded)<br/><b>Why:</b> Run only after a new metering transaction commits, on an async worker.<br/><b>Does:</b> Rehydrates TenantContext, calls the external publisher, updates publication metadata, handles failure, and clears context."]
    B["EventBridgeUsageEventPublisher.publish(MeteringEventRecorded)<br/><b>Why:</b> Deliver production usage to AWS.<br/><b>Does:</b> Builds JSON detail, calls EventBridgeClient.putEvents, and throws when any entry reports failure."]
    C["MeteringEventPublishListener.markPublished(UUID)<br/><b>Why:</b> Record successful external delivery.<br/><b>Does:</b> Finds the event, sets publishedAt, and saves it through JPA/RLS."]
    D["MeteringEventPublishListener.incrementAttempts(UUID)<br/><b>Why:</b> Preserve failure evidence for retry/operations.<br/><b>Does:</b> Increments publishAttempts and saves the event."]
    E["MeteringPublishReconciler.reconcileUnpublishedEvents()<br/><b>Why:</b> Cover process-crash and async-worker failure windows.<br/><b>Does:</b> Runs every 60 seconds, lists tenant IDs, sets one TenantContext per tenant, and clears it after each tenant."]
    F["MeteringPublishReconciler.reconcileForTenant(Instant cutoff)<br/><b>Why:</b> Retry only events older than the normal async head start.<br/><b>Does:</b> Queries unpublished events through RLS, publishes each, marks success, or increments attempts and logs after five failures."]
    G["UsageEventPublisher.publish(MeteringEventRecorded)<br/><b>Why:</b> Keep transport independent from the listener/reconciler.<br/><b>Does:</b> Dispatches to EventBridge in production or NoOpUsageEventPublisher in the test profile."]
    H["MeteringEventRepository.findUnpublishedOlderThan(Instant)<br/><b>Why:</b> Find durable outbox rows still needing delivery.<br/><b>Does:</b> Returns rows with publishedAt NULL and createdAt before cutoff."]
    I["EventBridge / downstream billing consumers<br/><b>Why:</b> Consume usage for billing or analytics.<br/><b>Does:</b> Receives UsageEventRecorded events on the configured bus; downstream infrastructure fans them out."]
    J["Retry or operational alert<br/><b>Why:</b> External delivery can fail after the local write is committed.<br/><b>Does:</b> Leaves publishedAt NULL, records attempts, and logs an alert-worthy condition at five failures."]

    A --> G
    G -->|success| B --> I
    B -->|success| C
    G -->|exception or partial EventBridge failure| D --> J
    E --> F --> H --> G
    F -->|success| C
    F -->|failure| D --> J
```

The async listener deliberately restores `TenantContext` because `ThreadLocal` does not cross the `@Async` thread boundary. The scheduled reconciler uses the same per-tenant pattern instead of bypassing RLS.

## 7. Subscription-tier administration

```mermaid
flowchart TD
    A["PATCH /admin/tenants/{tenantId}/subscription-tier<br/><b>Why:</b> Billing/operator workflow changes a tenant's plan.<br/><b>Does:</b> Sends the target tenant UUID and requested tier."]
    B["TenantAdminController.updateSubscriptionTier(UUID, UpdateTierRequest)<br/><b>Why:</b> Map the admin HTTP request to application logic.<br/><b>Does:</b> Parses the tier and calls TenantAdminService, then returns 204."]
    C["TenantAdminService.updateSubscriptionTier(UUID, SubscriptionTier)<br/><b>Why:</b> Keep the source of truth and cache aligned after a tier change.<br/><b>Does:</b> In one Postgres transaction, loads the tenant, updates subscription_tier, saves it, and writes the new cache value."]
    D["TenantRepository.findById(UUID) / save(Tenant)<br/><b>Why:</b> Read and persist the authoritative tier.<br/><b>Does:</b> Uses JPA through the runtime DataSource and database transaction."]
    E["TenantEntitlementsCache.put(UUID, SubscriptionTier)<br/><b>Why:</b> Make the next authorization decision see the new tier immediately.<br/><b>Does:</b> Writes the new five-minute DynamoDB cache entry; cache failure is logged without hiding the Postgres update."]
    F["HTTP 204 No Content<br/><b>Why:</b> Tier update completed.<br/><b>Does:</b> Confirms the admin operation without a response body."]
    A --> B --> C --> D --> E --> F
```

## 8. Complete lifecycle at a glance

```mermaid
flowchart LR
    A["EntitlementsEngineApplication.main(String[] args)<br/><b>Why:</b> Start the JVM application.<br/><b>Does:</b> Enters Spring Boot startup."] --> B["FlywayConfig.flyway -> Flyway.migrate<br/><b>Why:</b> Make the schema ready.<br/><b>Does:</b> Applies versioned tables and RLS policies."]
    B --> C["Spring application context<br/><b>Why:</b> Assemble runtime infrastructure.<br/><b>Does:</b> Creates DataSource, Security, AWS, async, and scheduled beans."]
    C --> D["index.html or platform service<br/><b>Why:</b> Initiate an operation.<br/><b>Does:</b> Sends an HTTP request to the API."]
    D --> E["TenantFilter.doFilterInternal(request, response, filterChain)<br/><b>Why:</b> Establish request tenant state.<br/><b>Does:</b> Sets ThreadLocal or rejects a protected request without a tenant."]
    E --> F["ReportController / MeteringController / TenantAdminController method<br/><b>Why:</b> Handle the mapped endpoint.<br/><b>Does:</b> Validates input and delegates to repository or service logic."]
    F --> G["EntitlementsService.canAccess or MeteringService.recordUsage<br/><b>Why:</b> Apply business policy.<br/><b>Does:</b> Evaluates ABAC or idempotency branches."]
    G --> H["TenantAwareDataSource.getConnection()<br/><b>Why:</b> Bind database work to the request tenant.<br/><b>Does:</b> Sets app.current_tenant on the borrowed connection."]
    H --> I["PostgreSQL RLS, constraints, and tables<br/><b>Why:</b> Enforce durable isolation and consistency.<br/><b>Does:</b> Filters rows, validates writes, and stores state."]
    I --> J["HTTP response<br/><b>Why:</b> Complete the caller interaction.<br/><b>Does:</b> Returns 200/201/204 or 400/403/404/409."]
    G --> K["MeteringEventPublishListener.onMeteringEventRecorded(event)<br/><b>Why:</b> Deliver committed new metering events asynchronously.<br/><b>Does:</b> Publishes externally and records success/failure metadata."]
    K --> L["EventBridgeUsageEventPublisher.publish(event)<br/><b>Why:</b> Send usage to downstream consumers.<br/><b>Does:</b> Calls AWS EventBridge and raises failures for retry."]
    L --> M["MeteringPublishReconciler.reconcileUnpublishedEvents()<br/><b>Why:</b> Recover events missed by the async path.<br/><b>Does:</b> Sweeps old unpublished rows every 60 seconds."]
    M --> I
```

## Source anchors

- Startup and configuration: [EntitlementsEngineApplication.java](../src/main/java/com/platform/entitlements/EntitlementsEngineApplication.java), [FlywayConfig.java](../src/main/java/com/platform/entitlements/config/FlywayConfig.java), [DataSourceConfig.java](../src/main/java/com/platform/entitlements/config/DataSourceConfig.java), [AwsConfig.java](../src/main/java/com/platform/entitlements/config/AwsConfig.java)
- Request and tenant boundary: [SecurityConfig.java](../src/main/java/com/platform/entitlements/security/SecurityConfig.java), [TenantFilter.java](../src/main/java/com/platform/entitlements/tenant/TenantFilter.java), [TenantAwareDataSource.java](../src/main/java/com/platform/entitlements/tenant/TenantAwareDataSource.java), [TenantContext.java](../src/main/java/com/platform/entitlements/tenant/TenantContext.java)
- Reports and ABAC: [ReportController.java](../src/main/java/com/platform/entitlements/domain/ReportController.java), [EntitlementsService.java](../src/main/java/com/platform/entitlements/abac/EntitlementsService.java), [PermissionRule.java](../src/main/java/com/platform/entitlements/abac/PermissionRule.java)
- Metering: [MeteringController.java](../src/main/java/com/platform/entitlements/metering/MeteringController.java), [MeteringService.java](../src/main/java/com/platform/entitlements/metering/MeteringService.java), [MeteringEventDao.java](../src/main/java/com/platform/entitlements/metering/MeteringEventDao.java), [MeteringEventPublishListener.java](../src/main/java/com/platform/entitlements/metering/MeteringEventPublishListener.java), [MeteringPublishReconciler.java](../src/main/java/com/platform/entitlements/metering/MeteringPublishReconciler.java)
- Browser entry points: [index.html](../index.html)
- Database isolation: [V2__enable_row_level_security.sql](../src/main/resources/db/migration/V2__enable_row_level_security.sql), [V4__ensure_metering_events_schema.sql](../src/main/resources/db/migration/V4__ensure_metering_events_schema.sql)