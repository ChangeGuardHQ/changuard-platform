package com.changeguard.connector.persistence;

import java.sql.Types;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Persists connector-owned GitHub integration state and selected repository
 * metadata.
 *
 * <p>
 * This repository intentionally stores only provider installation identifiers
 * and repository metadata. GitHub credentials, access tokens, and other secret
 * material must remain outside this persistence layer and are never written by
 * the methods in this class.</p>
 *
 * <p>
 * Organization-facing operations scope the lookup by both the
 * organization ID and integration ID. The organization ID is treated as the
 * authorization boundary, so a caller cannot use an integration ID from another
 * organization to mutate that integration.</p>
 *
 * <p>
 * Repository connection is idempotent: an existing repository record is updated
 * in place and reactivated when the provider reports it again. Disconnection is
 * represented by a status change rather than deletion, preserving the record
 * for audit and reconciliation purposes.</p>
 */
@Repository
public class ConnectorIntegrationRepository {

    private final JdbcClient jdbc;

    /**
     * Creates a repository backed by the configured {@link JdbcClient}.
     *
     * @param jdbc the JDBC client used for all integration and repository
     * persistence operations
     */
    public ConnectorIntegrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a GitHub integration for an internal organization.
     *
     * <p>
     * The installation ID is validated as positive before it is persisted. This
     * method is intended for authorized setup flows that have already resolved
     * the installation through the provider API.</p>
     *
     * @param organizationId the owning organization ID
     * @param integrationId the connector-managed integration ID
     * @param installationId the positive GitHub App installation ID
     */
    public void createGitHubIntegration(String organizationId, String integrationId, long installationId) {
        requireScope(organizationId, integrationId);
        Assert.isTrue(installationId > 0, "GitHub installation ID must be positive");
        jdbc.sql("""
                INSERT INTO connector.integrations (id, organization_id, provider, installation_id)
                VALUES (:integration, :organization, 'github', :installation)
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .param("installation", installationId).update();
    }

    public Optional<GitHubIntegration> findGitHubIntegration(String organizationId, String integrationId) {
        requireScope(organizationId, integrationId);
        return jdbc.sql("""
                SELECT id, organization_id, installation_id, status FROM connector.integrations
                WHERE id = :integration AND organization_id = :organization AND provider = 'github'
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .query((rs, row) -> new GitHubIntegration(rs.getString("id"), rs.getString("organization_id"),
                        rs.getLong("installation_id"), IntegrationStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * Internal lookup for HMAC-verified GitHub deliveries. Ownership comes from
     * the installation binding in storage, never a provider organization field.
     */
    public Optional<GitHubIntegration> findActiveGitHubInstallation(long installationId) {
        Assert.isTrue(installationId > 0, "GitHub installation ID must be positive");
        return jdbc.sql("""
                SELECT id, organization_id, installation_id, status FROM connector.integrations
                WHERE installation_id = :installation AND provider = 'github' AND status = 'ACTIVE'
                """)
                .param("installation", installationId)
                .query((rs, row) -> new GitHubIntegration(rs.getString("id"), rs.getString("organization_id"),
                        rs.getLong("installation_id"), IntegrationStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * Idempotently select a repository after an authorized provider API lookup.
     */
    @Transactional
    public void connectRepository(String organizationId, String integrationId, String repositoryId,
            String fullName, String defaultBranch) {
        requireScope(organizationId, integrationId);
        Assert.hasText(repositoryId, "Provider repository ID is required");
        Assert.hasText(fullName, "Repository full name is required");
        boolean active = jdbc.sql("""
                SELECT 1 FROM connector.integrations
                WHERE id = :integration AND organization_id = :organization
                  AND provider = 'github' AND status = 'ACTIVE'
                FOR SHARE
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .query(Integer.class).optional().isPresent();
        Assert.isTrue(active, "Active organization-owned GitHub integration is required");
        jdbc.sql("""
                INSERT INTO connector.connected_repositories
                    (integration_id, organization_id, repository_id, full_name, default_branch)
                VALUES (:integration, :organization, :repository, :name, :branch)
                ON CONFLICT (integration_id, repository_id) DO UPDATE
                    SET full_name = EXCLUDED.full_name, default_branch = EXCLUDED.default_branch,
                        status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .param("repository", repositoryId).param("name", fullName)
                .param("branch", defaultBranch, Types.VARCHAR).update();
    }

    public Optional<ConnectedRepository> findActiveRepository(String organizationId,
            String integrationId, String repositoryId) {
        requireScope(organizationId, integrationId);
        Assert.hasText(repositoryId, "Provider repository ID is required");
        return jdbc.sql("""
                SELECT r.repository_id, r.full_name, r.default_branch
                FROM connector.connected_repositories r
                JOIN connector.integrations i ON i.id = r.integration_id AND i.organization_id = r.organization_id
                WHERE r.organization_id = :organization AND r.integration_id = :integration
                  AND r.repository_id = :repository AND r.status = 'ACTIVE'
                  AND i.status = 'ACTIVE' AND i.provider = 'github'
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .param("repository", repositoryId)
                .query((rs, row) -> new ConnectedRepository(organizationId, integrationId,
                        rs.getString("repository_id"), rs.getString("full_name"), rs.getString("default_branch")))
                .optional();
    }

    public boolean setIntegrationStatus(String organizationId, String integrationId, IntegrationStatus status) {
        requireScope(organizationId, integrationId);
        Assert.notNull(status, "Integration status is required");
        return jdbc.sql("""
                UPDATE connector.integrations SET status = :status, updated_at = CURRENT_TIMESTAMP
                WHERE id = :integration AND organization_id = :organization AND provider = 'github'
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .param("status", status.name()).update() == 1;
    }

    public boolean disconnectRepository(String organizationId, String integrationId, String repositoryId) {
        requireScope(organizationId, integrationId);
        Assert.hasText(repositoryId, "Provider repository ID is required");
        return jdbc.sql("""
                UPDATE connector.connected_repositories SET status = 'DISCONNECTED', updated_at = CURRENT_TIMESTAMP
                WHERE integration_id = :integration AND organization_id = :organization AND repository_id = :repository
                """)
                .param("integration", integrationId).param("organization", organizationId)
                .param("repository", repositoryId).update() == 1;
    }

    private static void requireScope(String organizationId, String integrationId) {
        Assert.hasText(organizationId, "Organization ID is required");
        Assert.hasText(integrationId, "Integration ID is required");
    }

    public enum IntegrationStatus {
        ACTIVE, DISCONNECTED, REVOKED
    }

    public record GitHubIntegration(String integrationId, String organizationId,
            long installationId, IntegrationStatus status) {

    }

    public record ConnectedRepository(String organizationId, String integrationId,
            String repositoryId, String fullName, String defaultBranch) {

    }
}
