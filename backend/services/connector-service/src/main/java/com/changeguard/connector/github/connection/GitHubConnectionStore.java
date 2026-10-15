package com.changeguard.connector.github.connection;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.GitHubIntegration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transaction boundaries for callback replay protection and organization-owned selection. */
@Service
public class GitHubConnectionStore {
    private final JdbcClient jdbc;
    private final ConnectorIntegrationRepository integrations;

    public GitHubConnectionStore(JdbcClient jdbc, ConnectorIntegrationRepository integrations) {
        this.jdbc = jdbc;
        this.integrations = integrations;
    }

    @Transactional
    public void saveState(String hash, String organization, String administrator, long account,
            Instant expiresAt, Instant now) {
        jdbc.sql("DELETE FROM connector.github_installation_states WHERE expires_at <= :now")
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO connector.github_installation_states
                    (state_hash, organization_id, administrator_name, github_account_id, expires_at)
                VALUES (:hash, :organization, :administrator, :account, :expires)
                """).param("hash", hash).param("organization", organization).param("administrator", administrator)
                .param("account", account).param("expires", expiresAt.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<InstallationState> findState(String hash) {
        return jdbc.sql("""
                SELECT organization_id, administrator_name, github_account_id, expires_at, consumed_at
                FROM connector.github_installation_states WHERE state_hash = :hash
                """).param("hash", hash).query((rs, row) -> new InstallationState(rs.getString("organization_id"),
                        rs.getString("administrator_name"), rs.getLong("github_account_id"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("consumed_at") != null)).optional();
    }

    @Transactional
    public GitHubIntegration completeInstallation(String hash, String organization, String administrator,
            GitHubProvider.Installation installation, Optional<Long> expectedVersion) {
        // Recheck and consume under an exclusive lock: concurrent callbacks cannot both succeed.
        boolean valid = jdbc.sql("""
                SELECT 1 FROM connector.github_installation_states
                WHERE state_hash = :hash AND organization_id = :organization
                    AND administrator_name = :administrator AND github_account_id = :account
                    AND consumed_at IS NULL AND expires_at > clock_timestamp()
                FOR UPDATE
                """).param("hash", hash).param("organization", organization).param("administrator", administrator)
                .param("account", installation.accountId()).query(Integer.class).optional().isPresent();
        if (!valid) {
            throw GitHubInstallationState.invalid();
        }
        var previous = jdbc.sql("""
                SELECT status, connection_version FROM connector.integrations
                WHERE provider = 'github' AND installation_id = :installation FOR UPDATE
                """).param("installation", installation.installationId())
                .query((rs, row) -> new ConnectionState(rs.getString("status"), rs.getLong("connection_version")))
                .optional();
        if (previous.isPresent() && (expectedVersion.isPresent()
                ? expectedVersion.get() != previous.get().version() : !"ACTIVE".equals(previous.get().status()))) {
            throw changed();
        }
        String id = jdbc.sql("""
                INSERT INTO connector.integrations
                    (id, organization_id, provider, installation_id,
                     github_account_id, github_account_login, github_account_type)
                VALUES (:id, :organization, 'github', :installation, :account, :login, :type)
                ON CONFLICT (provider, installation_id) DO UPDATE
                    SET status = 'ACTIVE', github_account_id = EXCLUDED.github_account_id,
                        github_account_login = EXCLUDED.github_account_login,
                        github_account_type = EXCLUDED.github_account_type, updated_at = CURRENT_TIMESTAMP,
                        connection_version = connector.integrations.connection_version + 1
                    WHERE connector.integrations.organization_id = EXCLUDED.organization_id
                      AND (connector.integrations.github_account_id IS NULL
                           OR connector.integrations.github_account_id = EXCLUDED.github_account_id)
                RETURNING id
                """).param("id", UUID.randomUUID().toString()).param("organization", organization)
                .param("installation", installation.installationId()).param("account", installation.accountId())
                .param("login", installation.accountLogin()).param("type", installation.accountType())
                .query(String.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Installation is already bound to another organization or account"));
        if (previous.isPresent() && !"ACTIVE".equals(previous.get().status())) {
            // A restored connection must explicitly select repositories again.
            disconnectRepositories(organization, id);
        }
        jdbc.sql("UPDATE connector.github_installation_states SET consumed_at = clock_timestamp() WHERE state_hash = :hash")
                .param("hash", hash).update();
        return integrations.findGitHubIntegration(organization, id).orElseThrow();
    }

    public List<GitHubIntegration> listIntegrations(String organization) {
        return jdbc.sql("SELECT id FROM connector.integrations WHERE organization_id = :organization ORDER BY created_at, id")
                .param("organization", organization).query(String.class).list().stream()
                .map(id -> integrations.findGitHubIntegration(organization, id).orElseThrow()).toList();
    }

    public Optional<Long> installationVersion(long installationId) {
        return jdbc.sql("SELECT connection_version FROM connector.integrations WHERE installation_id = :installation")
                .param("installation", installationId).query(Long.class).optional();
    }

    public List<SelectedRepository> selectedRepositories(String organization, String integration) {
        return jdbc.sql("""
                SELECT repository_id, full_name, default_branch, status FROM connector.connected_repositories
                WHERE organization_id = :organization AND integration_id = :integration ORDER BY repository_id
                """).param("organization", organization).param("integration", integration)
                .query((rs, row) -> new SelectedRepository(rs.getString("repository_id"), rs.getString("full_name"),
                        rs.getString("default_branch"), rs.getString("status"))).list();
    }

    @Transactional
    public void selectRepositories(String organization, String integration, long account,
            long expectedVersion, List<GitHubProvider.GitHubRepository> selected) {
        if (lockIntegration(organization, integration, account, true) != expectedVersion) {
            throw changed();
        }
        if (selected.isEmpty()) {
            disconnectRepositories(organization, integration);
        } else {
            jdbc.sql("""
                    UPDATE connector.connected_repositories SET status = 'DISCONNECTED', updated_at = CURRENT_TIMESTAMP
                    WHERE organization_id = :organization AND integration_id = :integration
                      AND repository_id NOT IN (:selected)
                    """).param("organization", organization).param("integration", integration)
                    .param("selected", selected.stream().map(repo -> Long.toString(repo.repositoryId())).toList()).update();
            for (var repo : selected) {
                integrations.connectRepository(organization, integration, Long.toString(repo.repositoryId()),
                        repo.fullName(), repo.defaultBranch());
            }
        }
        advanceVersion(organization, integration);
    }

    @Transactional
    public void disconnect(String organization, String integration, long account) {
        lockIntegration(organization, integration, account, false);
        integrations.setIntegrationStatus(organization, integration,
                ConnectorIntegrationRepository.IntegrationStatus.DISCONNECTED);
        disconnectRepositories(organization, integration);
    }

    @Transactional
    public void disconnectRepository(String organization, String integration, long account, String repository) {
        lockIntegration(organization, integration, account, false);
        if (!integrations.disconnectRepository(organization, integration, repository)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connected repository not found");
        }
        advanceVersion(organization, integration);
    }

    private long lockIntegration(String organization, String integration, long account, boolean requireActive) {
        var status = jdbc.sql("""
                SELECT status, connection_version FROM connector.integrations
                WHERE organization_id = :organization AND id = :integration AND github_account_id = :account
                FOR UPDATE
                """).param("organization", organization).param("integration", integration).param("account", account)
                .query((rs, row) -> new ConnectionState(rs.getString("status"), rs.getLong("connection_version")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "GitHub integration not found"));
        if (requireActive && !"ACTIVE".equals(status.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub integration is not active");
        }
        return status.version();
    }

    private void advanceVersion(String organization, String integration) {
        jdbc.sql("""
                UPDATE connector.integrations SET connection_version = connection_version + 1,
                    updated_at = CURRENT_TIMESTAMP WHERE organization_id = :organization AND id = :integration
                """).param("organization", organization).param("integration", integration).update();
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "GitHub connection changed during verification; retry the request");
    }

    private void disconnectRepositories(String organization, String integration) {
        jdbc.sql("""
                UPDATE connector.connected_repositories SET status = 'DISCONNECTED', updated_at = CURRENT_TIMESTAMP
                WHERE organization_id = :organization AND integration_id = :integration
                """).param("organization", organization).param("integration", integration).update();
    }

    public record InstallationState(String organizationId, String administratorName, long githubAccountId,
            Instant expiresAt, boolean consumed) { }
    public record SelectedRepository(String repositoryId, String fullName, String defaultBranch, String status) { }
    private record ConnectionState(String status, long version) { }
}
