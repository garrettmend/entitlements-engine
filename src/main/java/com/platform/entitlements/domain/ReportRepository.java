/** Provides JPA persistence operations for reports while PostgreSQL RLS enforces tenant filtering. */
package com.platform.entitlements.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Notice there is NO tenant_id parameter on findAll(), and no custom
 * @Query with a WHERE tenant_id = ?1 anywhere in this interface. That is
 * the point: a plain findAll() is already tenant-safe because Postgres RLS
 * (V2__enable_row_level_security.sql) filters the rows before they ever
 * reach Hibernate. A developer who forgets to add a tenant filter to a new
 * query method here still cannot leak another tenant's rows — the database
 * won't return them.
 *
 * This does NOT mean you should never think about tenant_id in application
 * code — you still need it in TenantContext for the DataSource wrapper to
 * set the session variable in the first place. It means the *query logic*
 * itself doesn't need to duplicate that filter everywhere.
 */
public interface ReportRepository extends JpaRepository<Report, UUID> {
}
