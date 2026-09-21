/** Provides standard JPA access to tenant registry rows. */
package com.platform.entitlements.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
	Optional<Tenant> findFirstByOrderByCreatedAtAsc();

	@Query(value = "SELECT * FROM tenants ORDER BY random() LIMIT 1", nativeQuery = true)
	Optional<Tenant> findRandomTenant();
}
