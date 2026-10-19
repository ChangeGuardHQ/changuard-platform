package com.changeguard.change.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL uniqueness serializes concurrent receipts without a check-then-insert race. */
@Repository
public class ProcessedEventRepository {
    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIfAbsent(String organization, String eventId, String fingerprint, UUID changeId) {
        jdbc.sql("""
                INSERT INTO change.processed_events (organization_id, event_id, fingerprint, change_id)
                VALUES (:organization, :event, :fingerprint, :change)
                ON CONFLICT (organization_id, event_id) DO NOTHING
                """).param("organization", organization).param("event", eventId)
                .param("fingerprint", fingerprint).param("change", changeId).update();
    }

    public Optional<ProcessedEvent> find(String organization, String eventId) {
        return jdbc.sql("""
                SELECT organization_id, event_id, fingerprint, change_id, processed_at
                FROM change.processed_events WHERE organization_id = :organization AND event_id = :event
                """).param("organization", organization).param("event", eventId)
                .query((row, index) -> new ProcessedEvent(row.getString("organization_id"), row.getString("event_id"),
                        row.getString("fingerprint"), row.getObject("change_id", UUID.class),
                        row.getTimestamp("processed_at").toInstant())).optional();
    }
}
