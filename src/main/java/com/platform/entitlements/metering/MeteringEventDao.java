package com.platform.entitlements.metering;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole idempotency guarantee lives in this one query. We deliberately
 * do NOT do "SELECT to check if it exists, then INSERT if not" — that has
 * a race window: two near-simultaneous duplicate requests (a client retry
 * firing while the first attempt is still in flight, which is the exact
 * scenario idempotency keys exist to handle) can both pass the SELECT
 * check before either has committed an INSERT, and both proceed to insert,
 * defeating the whole point.
 *
 * INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id
 * pushes the check-and-insert into a single atomic operation at the
 * database level. Postgres's unique index guarantees only one of two
 * concurrent inserts with the same key can ever succeed; the loser's
 * INSERT returns zero rows (not an error, not a thrown exception) rather
 * than racing.
 */
@Repository
public class MeteringEventDao {

    private final JdbcTemplate jdbcTemplate;

    public MeteringEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to insert a new event. Returns the new row's id if this call
     * won the race (i.e. this is genuinely the first time this idempotency
     * key has been seen for this tenant), or empty if a row with this
     * (tenant_id, idempotency_key) already existed — meaning this is a
     * duplicate/retry and the caller should look up and return the
     * EXISTING row's data, not treat this as a new event.
     */
    public Optional<UUID> insertIfAbsent(UUID tenantId, String idempotencyKey,
                                          String eventType, BigDecimal quantity) {
        List<UUID> insertedIds = jdbcTemplate.query(
                """
                INSERT INTO metering_events (id, tenant_id, idempotency_key, event_type, quantity)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                tenantId, idempotencyKey, eventType, quantity);

        return insertedIds.stream().findFirst();
    }
}
