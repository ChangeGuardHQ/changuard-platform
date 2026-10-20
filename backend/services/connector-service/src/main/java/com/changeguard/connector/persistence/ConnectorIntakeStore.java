package com.changeguard.connector.persistence;

import java.time.ZoneOffset;
import java.sql.Types;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import com.changeguard.connector.event.CodeLifecycleEvent;

import com.changeguard.connector.event.PullRequestMergedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists a verified delivery receipt and its canonical outbox records in a single
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
    public boolean accept(PullRequestMergedEvent event) {
        return accept(event, null);
    }

    /** Also verifies the provider account while holding the integration/repository locks. */
    @Transactional
    public boolean accept(PullRequestMergedEvent event, Long repositoryOwnerId) {
        Assert.notNull(event, "Canonical event is required");
        Assert.isTrue("github".equals(event.source().provider()), "GitHub event source is required");
        String organizationId = event.organizationId();
        String integrationId = event.source().integrationId();
        String repositoryId = event.payload().repositoryId();

        return persist(organizationId, integrationId, repositoryId, event.payload().deliveryId(), event.receivedAt(),
                "pull_request", repositoryOwnerId, List.of(new StoredEvent(event.eventId(), event.eventType(),
                        event.eventVersion(), json.writeValueAsString(event))));
    }

    /** One push receipt protects all of its per-commit outbox rows in the same revocation-safe transaction. */
    @Transactional
    public boolean acceptLifecycle(List<CodeLifecycleEvent> events, Long repositoryOwnerId) {
        Assert.notEmpty(events, "Lifecycle events are required");
        Assert.isTrue(events.size() <= 2048, "A delivery contains at most 2048 commits");
        var first = events.getFirst();
        String organizationId = first.organizationId();
        String integrationId = first.source().integrationId();
        String repositoryId = first.payload().repositoryId();
        var rows = events.stream().map(event -> {
            Assert.isTrue(organizationId.equals(event.organizationId()) && integrationId.equals(event.source().integrationId())
                    && repositoryId.equals(event.payload().repositoryId())
                    && first.payload().deliveryId().equals(event.payload().deliveryId())
                    && first.eventType().equals(event.eventType()) && first.receivedAt().equals(event.receivedAt())
                    && "github".equals(event.source().provider()), "One receipt must contain one trusted delivery scope");
            return new StoredEvent(event.eventId(), event.eventType(), event.eventVersion(), json.writeValueAsString(event));
        }).toList();
        return persist(organizationId, integrationId, repositoryId, first.payload().deliveryId(), first.receivedAt(),
                "CommitCreated".equals(first.eventType()) ? "push" : "pull_request", repositoryOwnerId, rows);
    }

    private record StoredEvent(String id, String type, int version, String payload) { }

    private boolean persist(String organizationId, String integrationId, String repositoryId, String deliveryId,
            Instant receivedAt, String providerType, Long repositoryOwnerId, List<StoredEvent> events) {
        // Shared row locks prevent disconnect/revocation from racing with this acceptance transaction.
        boolean active = jdbc.sql("""
                SELECT 1 FROM connector.connected_repositories r
                JOIN connector.integrations i ON i.id = r.integration_id AND i.organization_id = r.organization_id
                WHERE r.organization_id = :organization AND r.integration_id = :integration
                  AND r.repository_id = :repository AND r.status = 'ACTIVE'
                  AND i.status = 'ACTIVE' AND i.provider = 'github'
                  AND (i.github_account_id IS NULL OR i.github_account_id = :account)
                FOR SHARE OF i, r
                """)
                .param("organization", organizationId).param("integration", integrationId)
                .param("repository", repositoryId).param("account", repositoryOwnerId, Types.BIGINT)
                .query(Integer.class).optional().isPresent();
        if (!active) {
            throw new RepositoryNotConnectedException();
        }

        // V3 retains both delivery keys for older writers. Handle either unique
        // index so simultaneous inserts cannot race through the other key.
        UUID receiptId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO connector.webhook_receipts
                    (id, organization_id, integration_id, repository_id, delivery_id, provider_event_type, received_at)
                VALUES (:receipt, :organization, :integration, :repository, :delivery, :providerType, :received)
                ON CONFLICT DO NOTHING
                """)
                .param("receipt", receiptId).param("organization", organizationId)
                .param("integration", integrationId).param("repository", repositoryId)
                .param("delivery", deliveryId).param("providerType", providerType)
                .param("received", receivedAt.atOffset(ZoneOffset.UTC)).update();
        if (inserted == 0) {
            return false;
        }

        for (var event : events) {
            jdbc.sql("""
                INSERT INTO connector.outbox_events
                    (event_id, receipt_id, organization_id, integration_id, event_type, event_version,
                     topic, partition_key, payload)
                VALUES (:event, :receipt, :organization, :integration, :type, :version,
                        :topic, :key, CAST(:payload AS jsonb))
                """)
                .param("event", event.id()).param("receipt", receiptId)
                .param("organization", organizationId).param("integration", integrationId)
                .param("type", event.type()).param("version", event.version())
                .param("topic", codeEventsTopic).param("key", repositoryId)
                .param("payload", event.payload()).update();
        }
        return true;
    }
}
