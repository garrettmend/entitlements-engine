/** Stores and clears the current tenant ID in a thread-local request context. */
package com.platform.entitlements.tenant;

/**
 * Holds the current request's tenant id in a ThreadLocal.
 *
 * IMPORTANT: this is only half the isolation story. This value is used to
 * SET the Postgres session variable `app.current_tenant` on the JDBC
 * connection for the lifetime of the request (see TenantConnectionListener).
 * The actual enforcement happens in Postgres via Row-Level Security policies
 * (see V2__enable_row_level_security.sql) — NOT here. If you only trusted
 * this ThreadLocal + application-level WHERE clauses, a single missed
 * clause in a new repository method would leak cross-tenant data. RLS makes
 * that structurally impossible because the database itself refuses to
 * return rows that don't match the policy, regardless of the query.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null/blank");
        }
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant set on this thread. Every request must pass through TenantFilter " +
                    "before reaching a repository. If you're calling repository code from a " +
                    "background thread (e.g. @Async, a scheduled job), you must propagate the " +
                    "tenant id explicitly and call TenantContext.setTenantId() on that thread.");
        }
        return tenantId;
    }

    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }

    /** Always call this in a finally block. Thread pools reuse threads, so a
     *  leaked tenant id from one request could leak into the next request
     *  handled by the same worker thread. */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
