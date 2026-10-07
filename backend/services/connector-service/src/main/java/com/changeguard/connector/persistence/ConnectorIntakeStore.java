package com.changeguard.connector.persistence;

import java.time.ZoneOffset;
import java.util.UUID;

import com.changeguard.connector.event.CodeChangeMergedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists a verified merge receipt and canonical outbox record in a single
 * PostgreSQL transaction.
 */
@Service
public class ConnectorIntakeStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String codeEventsTopic;

    public ConnectorIntakeStore(JdbcClient jdbc, JsonMapper json,
            @Value("${changeguard.kafka.topics.code-events}") String codeEventsTopic) {
        Assert.hasText(codeEventsTopic, "Code events topic must not be blank");
        this.jdbc = jdbc;
        this.json = json;
        this.codeEventsTopic = codeEventsTopic;
    }

    /**
     * Accepts a normalized, HMAC-verified GitHub merge event after its
     * integration context has been established by the caller.
     *
     * <p>
     * The receipt and canonical outbox row are committed together. A new
     * delivery returns {@code true}; a duplicate delivery ID returns
     * {@code false} without creating another outbox event. The transaction also
     * locks the repository and integration rows so revocation or disconnect
     * operations cannot race with acceptance. Database or serialization
     * failures roll back both writes, and this method does not publish to
     * Kafka.
     */
    @Transactional
    public boolean accept(CodeChangeMergedEvent event) {
        Assert.notNull(event, "Canonical event is required");
        Assert.isTrue("github".equals(event.source().provider()), "GitHub event source is required");
        String organizationId = event.organizationId();
        String integrationId = event.source().integrationId();
        String repositoryId = event.payload().repositoryId();

        // Shared row locks prevent disconnect/revocation from racing with this acceptance transaction.
        boolean active = jdbc.sql("""
                SELECT 1 FROM connector.connected_repositories r
                JOIN connector.integrations i ON i.id = r.integration_id AND i.organization_id = r.organization_id
                WHERE r.organization_id = :organization AND r.integration_id = :integration
                  AND r.repository_id = :repository AND r.status = 'ACTIVE'
                  AND i.status = 'ACTIVE' AND i.provider = 'github'
                FOR SHARE OF i, r
                """)
                .param("organization", organizationId).param("integration", integrationId)
                .param("repository", repositoryId).query(Integer.class).optional().isPresent();
        if (!active) {
            throw new RepositoryNotConnectedException();
        }

        UUID receiptId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO connector.webhook_receipts
                    (id, organization_id, integration_id, repository_id, delivery_id, provider_event_type, received_at)
                VALUES (:receipt, :organization, :integration, :repository, :delivery, 'pull_request', :received)
                ON CONFLICT (integration_id, delivery_id) DO NOTHING
                """)
                .param("receipt", receiptId).param("organization", organizationId)
                .param("integration", integrationId).param("repository", repositoryId)
                .param("delivery", event.payload().deliveryId())
                .param("received", event.receivedAt().atOffset(ZoneOffset.UTC)).update();
        if (inserted == 0) {
            return false;
        }

        jdbc.sql("""
                INSERT INTO connector.outbox_events
                    (event_id, receipt_id, organization_id, integration_id, event_type, event_version,
                     topic, partition_key, payload)
                VALUES (:event, :receipt, :organization, :integration, :type, :version,
                        :topic, :key, CAST(:payload AS jsonb))
                """)
                .param("event", event.eventId()).param("receipt", receiptId)
                .param("organization", organizationId).param("integration", integrationId)
                .param("type", event.eventType()).param("version", event.eventVersion())
                .param("topic", codeEventsTopic).param("key", repositoryId)
                .param("payload", json.writeValueAsString(event)).update();
        return true;
    }
}
