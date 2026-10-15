package com.changeguard.connector.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import com.changeguard.connector.github.GitHubLifecycleNormalizer.LifecycleDelivery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists GitHub lifecycle webhook receipts and applies the corresponding
 * integration updates.
 *
 * <p>
 * The store serializes receipt insertion with the integration row lock so that
 * duplicate deliveries are ignored while revocations and repository disconnects
 * are committed atomically with the webhook acknowledgement.</p>
 */
@Service
public class GitHubLifecycleStore {

    private final JdbcClient jdbc;

    public GitHubLifecycleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Accepts a normalized GitHub lifecycle event and records it if it has not
     * already been seen.
     *
     * @param delivery the normalized payload describing the GitHub event and
     * any repository removals
     * @param deliveryId the provider-specific delivery identifier used as the
     * idempotency key
     * @param receivedAt the server timestamp when the webhook was received
     * @return {@code true} when the receipt was inserted and the state change
     * was applied, {@code false} when the same delivery had already been
     * processed
     * @throws RepositoryNotConnectedException when the installation is not
     * connected to an organization or the webhook account does not match the
     * connected account
     */
    @Transactional
    public boolean accept(LifecycleDelivery delivery, String deliveryId, Instant receivedAt) {
        var integration = jdbc.sql("""
                SELECT id, organization_id, github_account_id FROM connector.integrations
                WHERE provider = 'github' AND installation_id = :installation FOR UPDATE
                """).param("installation", delivery.installationId())
                .query((rs, row) -> new Owner(rs.getString("id"), rs.getString("organization_id"),
                        rs.getObject("github_account_id", Long.class)))
                .optional().orElseThrow(RepositoryNotConnectedException::new);
        if (integration.accountId() != null && delivery.accountId() != null
                && !Objects.equals(integration.accountId(), delivery.accountId())) {
            throw new RepositoryNotConnectedException();
        }
        int inserted = jdbc.sql("""
                INSERT INTO connector.webhook_receipts
                    (id, organization_id, integration_id, delivery_id, provider_event_type, provider_action, received_at)
                VALUES (:receipt, :organization, :integration, :delivery, :event, :action, :received)
                ON CONFLICT DO NOTHING
                """).param("receipt", UUID.randomUUID()).param("organization", integration.organizationId())
                .param("integration", integration.id())
                .param("delivery", deliveryId).param("event", delivery.eventType()).param("action", delivery.action())
                .param("received", receivedAt.atOffset(ZoneOffset.UTC)).update();
        if (inserted == 0) {
            return false;
        }
        if ("installation".equals(delivery.eventType())) {
            jdbc.sql("""
                    UPDATE connector.integrations SET status = CASE WHEN status = 'REVOKED' THEN status ELSE :status END,
                        connection_version = connection_version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :integration AND organization_id = :organization
                    """).param("status", "deleted".equals(delivery.action()) ? "REVOKED" : "DISCONNECTED")
                    .param("integration", integration.id()).param("organization", integration.organizationId()).update();
        } else if (!delivery.removedRepositoryIds().isEmpty()) {
            jdbc.sql("""
                    UPDATE connector.integrations SET connection_version = connection_version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = :integration AND organization_id = :organization
                    """).param("integration", integration.id()).param("organization", integration.organizationId()).update();
            jdbc.sql("""
                    UPDATE connector.connected_repositories SET status = 'DISCONNECTED', updated_at = CURRENT_TIMESTAMP
                    WHERE integration_id = :integration AND organization_id = :organization
                      AND repository_id IN (:repositories)
                    """).param("integration", integration.id()).param("organization", integration.organizationId())
                    .param("repositories", delivery.removedRepositoryIds()).update();
        }
        return true;
    }

    private record Owner(String id, String organizationId, Long accountId) {

    }
}
