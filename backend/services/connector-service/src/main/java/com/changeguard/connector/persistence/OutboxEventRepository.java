package com.changeguard.connector.persistence;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

/**
 * Persistent storage operations for the connector's outbox relay.
 *
 * <p>
 * {@link #claimNext(Duration)} atomically leases a due event using PostgreSQL's
 * SKIP LOCKED. Broker I/O happens after that short transaction commits. Only the
 * current lease owner may mark publication or schedule a retry; an expired lease
 * lets another worker recover an interrupted send. {@link #findPending(int)} is
 * an inspection method and does not claim work.</p>
 *
 * <p>
 * Reads are bounded and deterministic, and are intentionally not scoped to a
 * single organization so that an internal relay can process the complete outbox
 * in stable order.</p>
 */
@Repository
public class OutboxEventRepository {

    private final JdbcClient jdbc;

    /**
     * Creates a repository backed by the configured JDBC client.
     */
    public OutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns up to {@code limit} pending events in ascending creation order.
     *
     * <p>
     * This method only reads records; it does not change their status or claim
     * a lease.</p>
     *
     * @param limit maximum number of events to return, from 1 through 1000
     * @return the selected pending events
     */
    public List<OutboxEvent> findPending(int limit) {
        Assert.isTrue(limit > 0 && limit <= 1000, "Outbox batch size must be between 1 and 1000");
        return jdbc.sql("""
                SELECT event_id, organization_id, integration_id, event_type, event_version,
                       topic, partition_key, payload::text, created_at
                FROM connector.outbox_events WHERE status = 'PENDING'
                ORDER BY created_at, event_id LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, row) -> mapEvent(rs))
                .list();
    }

    /**
     * Claims one due row, excluding active leases. Database time avoids clock
     * differences between workers. A fresh token fences acknowledgements from
     * previous workers after recovery.
     */
    public Optional<ClaimedEvent> claimNext(Duration leaseDuration) {
        requireDuration(leaseDuration);
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT event_id FROM connector.outbox_events
                    WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()
                      AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
                    ORDER BY created_at, event_id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE connector.outbox_events o
                SET lease_token = :token,
                    lease_expires_at = clock_timestamp() + :leaseMillis * INTERVAL '1 millisecond',
                    attempt_count = attempt_count + 1
                FROM candidate c WHERE o.event_id = c.event_id
                RETURNING o.event_id, organization_id, integration_id, event_type, event_version,
                          topic, partition_key, payload::text, created_at, attempt_count
                """)
                .param("token", token).param("leaseMillis", leaseDuration.toMillis())
                .query((rs, row) -> new ClaimedEvent(mapEvent(rs), token, rs.getInt("attempt_count")))
                .optional();
    }

    /** Records publication only after acknowledgement and only for the current lease. */
    public boolean markPublished(ClaimedEvent claim) {
        return jdbc.sql("""
                UPDATE connector.outbox_events
                SET status = 'PUBLISHED', published_at = clock_timestamp(),
                    lease_token = NULL, lease_expires_at = NULL, last_failure_code = NULL
                WHERE event_id = :event AND status = 'PENDING' AND lease_token = :token
                """)
                .param("event", claim.event().eventId()).param("token", claim.leaseToken()).update() == 1;
    }

    /** Retains the original event and stores retry timing, without provider-controlled errors. */
    public boolean releaseForRetry(ClaimedEvent claim, Duration delay, String failureCode) {
        requireDuration(delay);
        Assert.isTrue(List.of("SEND_FAILED", "ACK_TIMEOUT", "INTERRUPTED", "INVALID_PAYLOAD")
                .contains(failureCode), "Unknown outbox failure code");
        return jdbc.sql("""
                UPDATE connector.outbox_events
                SET next_attempt_at = clock_timestamp() + :delayMillis * INTERVAL '1 millisecond',
                    lease_token = NULL, lease_expires_at = NULL, last_failure_code = :failure
                WHERE event_id = :event AND status = 'PENDING' AND lease_token = :token
                """)
                .param("delayMillis", delay.toMillis()).param("failure", failureCode)
                .param("event", claim.event().eventId()).param("token", claim.leaseToken()).update() == 1;
    }

    private static void requireDuration(Duration duration) {
        Assert.isTrue(duration != null && duration.toMillis() > 0, "Duration must be at least one millisecond");
    }

    private static OutboxEvent mapEvent(ResultSet rs) throws SQLException {
        return new OutboxEvent(rs.getString("event_id"), rs.getString("organization_id"),
                rs.getString("integration_id"), rs.getString("event_type"), rs.getInt("event_version"),
                rs.getString("topic"), rs.getString("partition_key"), rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record ClaimedEvent(OutboxEvent event, UUID leaseToken, int attemptCount) {
    }

    public record OutboxEvent(String eventId, String organizationId, String integrationId,
            String eventType, int eventVersion, String topic, String partitionKey,
            String payload, Instant createdAt) {

    }
}
