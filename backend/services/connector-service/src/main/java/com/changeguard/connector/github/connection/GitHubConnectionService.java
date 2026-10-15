package com.changeguard.connector.github.connection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.GitHubIntegration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.IntegrationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubConnectionService {
    private final GitHubConnectionProperties properties;
    private final GitHubOrganizationAuthorizer authorization;
    private final GitHubInstallationState states;
    private final GitHubProvider provider;
    private final GitHubConnectionStore store;
    private final ConnectorIntegrationRepository integrations;

    public GitHubConnectionService(GitHubConnectionProperties properties, GitHubOrganizationAuthorizer authorization,
            GitHubInstallationState states, GitHubProvider provider, GitHubConnectionStore store,
            ConnectorIntegrationRepository integrations) {
        this.properties = properties;
        this.authorization = authorization;
        this.states = states;
        this.provider = provider;
        this.store = store;
        this.integrations = integrations;
    }

    public InstallationStart start(String organization, Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        String state = states.create();
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.stateTtl());
        store.saveState(states.verifiedHash(state), organization, caller.getName(), binding.githubAccountId(), expiry, now);
        return new InstallationStart("https://github.com/apps/" + properties.appSlug()
                + "/installations/new?state=" + state, expiry);
    }

    public GitHubIntegration complete(String state, long installationId, Authentication caller) {
        if (installationId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive installation ID is required");
        }
        String hash = states.verifiedHash(state);
        var stored = store.findState(hash).orElseThrow(GitHubInstallationState::invalid);
        var binding = authorization.requireAdministrator(stored.organizationId(), caller);
        if (!stored.administratorName().equals(caller.getName()) || stored.consumed()
                || !stored.expiresAt().isAfter(Instant.now()) || stored.githubAccountId() != binding.githubAccountId()) {
            throw GitHubInstallationState.invalid();
        }
        var version = store.installationVersion(installationId);
        var installation = verifiedInstallation(installationId, binding.githubAccountId());
        // Provider calls happen outside the transaction; consumption and binding commit atomically afterward.
        return store.completeInstallation(hash, stored.organizationId(), caller.getName(), installation, version);
    }

    public List<GitHubIntegration> list(String organization, Authentication caller) {
        authorization.requireAdministrator(organization, caller);
        return store.listIntegrations(organization);
    }

    public List<GitHubProvider.GitHubRepository> available(String organization, String integration, Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        var owned = ownedIntegration(organization, integration, binding.githubAccountId(), true);
        verifiedInstallation(owned.installationId(), binding.githubAccountId());
        return provider.repositories(owned.installationId()).stream()
                .filter(repo -> repo.ownerId() == binding.githubAccountId()).toList();
    }

    public List<GitHubConnectionStore.SelectedRepository> selected(String organization, String integration,
            Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        ownedIntegration(organization, integration, binding.githubAccountId(), false);
        return store.selectedRepositories(organization, integration);
    }

    public void select(String organization, String integration, Set<Long> ids, Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        var owned = ownedIntegration(organization, integration, binding.githubAccountId(), true);
        verifiedInstallation(owned.installationId(), binding.githubAccountId());
        var accessible = provider.repositories(owned.installationId()).stream()
                .filter(repo -> repo.ownerId() == binding.githubAccountId() && ids.contains(repo.repositoryId())).toList();
        if (accessible.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository is not accessible to the owned installation");
        }
        store.selectRepositories(organization, integration, binding.githubAccountId(), owned.connectionVersion(), accessible);
    }

    public void disconnect(String organization, String integration, Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        store.disconnect(organization, integration, binding.githubAccountId());
    }

    public void disconnectRepository(String organization, String integration, String repository, Authentication caller) {
        var binding = authorization.requireAdministrator(organization, caller);
        store.disconnectRepository(organization, integration, binding.githubAccountId(), repository);
    }

    private GitHubIntegration ownedIntegration(String organization, String integration, long account, boolean active) {
        var result = integrations.findGitHubIntegration(organization, integration)
                .filter(found -> Objects.equals(found.githubAccountId(), account))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub integration not found"));
        if (active && result.status() != IntegrationStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub integration is not active");
        }
        return result;
    }

    private GitHubProvider.Installation verifiedInstallation(long id, long account) {
        var installation = provider.installation(id);
        if (installation.installationId() != id || installation.appId() != properties.appId()
                || installation.accountId() != account || installation.suspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Installation does not belong to the trusted GitHub account");
        }
        return installation;
    }

    public record InstallationStart(String installationUrl, Instant expiresAt) { }
}
