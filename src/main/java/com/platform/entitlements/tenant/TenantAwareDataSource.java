package com.platform.entitlements.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Wraps the pooled (Hikari) DataSource so that every connection checked out
 * from the pool has Postgres session variable `app.current_tenant` set to
 * TenantContext.getTenantId() for the duration of that checkout, and RESET
 * back to empty before the physical connection returns to the pool.
 *
 * WHY THIS MATTERS: HikariCP reuses physical connections across requests.
 * If we only SET the variable once and never reset it, a connection that
 * previously served Tenant A could be handed to a request for Tenant B
 * without the variable being updated correctly in some edge cases (e.g. if
 * the SET happens on borrow but a leaked/cached statement bypasses it), and
 * worse, if a connection is borrowed OUTSIDE the normal filter->repository
 * path (some background task, a leaked connection), it could silently carry
 * the wrong tenant. Resetting on release is a defense-in-depth measure:
 * a "clean" connection with no tenant set will make every RLS-protected
 * query return zero rows (fail closed) rather than another tenant's rows
 * (fail open), because the RLS policy compares against a variable that
 * defaults to '' / unset, which matches no tenant_id.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection physical = super.getConnection();
        applyTenant(physical);
        return wrapWithResetOnClose(physical);
    }

    private void applyTenant(Connection connection) throws SQLException {
        String tenantId = TenantContext.isSet() ? TenantContext.getTenantId() : "";
        // set_config(..., is_local => false) sets it for the session (this
        // checkout), not just the current transaction — we want it to hold
        // across multiple statements/transactions within one request.
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT set_config('app.current_tenant', '" + escapeLiteral(tenantId) + "', false)");
        }
    }

    private String escapeLiteral(String value) {
        // tenant ids are UUIDs from validated JWT claims, but never trust
        // that blindly when building SQL by string concatenation.
        return value.replace("'", "''");
    }

    private Connection wrapWithResetOnClose(Connection physical) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                try (Statement st = physical.createStatement()) {
                    st.execute("SELECT set_config('app.current_tenant', '', false)");
                } catch (SQLException ignored) {
                    // if the connection is already broken, closing it is
                    // still the right move; don't mask that with a reset failure
                }
                return method.invoke(physical, args);
            }
            try {
                return method.invoke(physical, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }
}
