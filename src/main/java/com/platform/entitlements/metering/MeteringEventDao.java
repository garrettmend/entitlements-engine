/** Performs the atomic PostgreSQL insert that guarantees tenant-scoped idempotency under concurrency. */
package com.platform.entitlements.metering;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * WHY THIS FILE EXISTS:
 * This DAO owns the one database operation that creates a metering event.
 * It uses PostgreSQL's atomic INSERT ... ON CONFLICT behavior so concurrent
 * requests with the same tenant and idempotency key cannot create duplicate
 * usage rows. The service layer decides whether an empty result is a replay
 * or a payload conflict; this class only reports whether the insert won.
 *
 * RUNTIME FLOW:
 * 1. Receive the tenant and usage payload from MeteringService.
 * 2. Attempt one atomic insert guarded by the database unique constraint.
 * 3. Return the generated event ID for a new row, or Optional.empty() for a duplicate.
 *
 * THE RACE CONDITION PROBLEM:
 * If we did this the naive way:
 * a) SELECT to see if key exists
 * b) IF NOT, then INSERT
 * 
 * If two requests arrive at the exact same millisecond, both threads run step (a) 
 * at the same time. Both see "not found". Both proceed to step (b). We double-bill 
 * the customer. 
 * 
 * THE POSTGRESQL SOLUTION:
 * This class uses Postgres's "ON CONFLICT... DO NOTHING". It forces the database 
 * engine to handle both the check and the insert as a single, atomic operation 
 * relying on a unique index in the database itself.
 */
@Repository
public class MeteringEventDao {

    // Spring's core tool for executing raw SQL queries securely.
    private final JdbcTemplate jdbcTemplate;

    public MeteringEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to insert a new event. 
     * Returns:
     * - An ID if successful (meaning this is a new event).
     * - Optional.empty() if it hits a conflict (meaning this is a duplicate).
     */
    public Optional<UUID> insertIfAbsent(UUID tenantId, String idempotencyKey,
                                         String eventType, BigDecimal quantity) {

        // Step 1: execute one parameterized SQL operation through Spring JDBC.
        // query() returns one generated ID for a new row or no rows on conflict.
        List<UUID> insertedIds = jdbcTemplate.query(
                """
            -- Step 2: let PostgreSQL generate the durable event ID and insert the payload.
                INSERT INTO metering_events (id, tenant_id, idempotency_key, event_type, quantity)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                
            -- Step 3: make the tenant-scoped uniqueness check and insert atomic.
            -- A duplicate becomes an empty result instead of an exception.
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                
            -- Step 4: return an ID only when this call created the row.
                RETURNING id
                """,
            // Step 5: map PostgreSQL's returned UUID into Java's UUID type.
                (rs, rowNum) -> (UUID) rs.getObject("id"),
            // Step 6: bind values as JDBC parameters rather than concatenating SQL.
                tenantId, idempotencyKey, eventType, quantity);

        // Step 7: expose the outcome to MeteringService: present means new,
        // empty means an existing tenant/key pair won the race.
        return insertedIds.stream().findFirst();
    }
}