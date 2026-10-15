package com.changeguard.connector.api;

import java.util.List;
import java.util.Set;

import com.changeguard.connector.github.connection.GitHubConnectionService;
import com.changeguard.connector.github.connection.GitHubConnectionStore.SelectedRepository;
import com.changeguard.connector.github.connection.GitHubProvider.GitHubRepository;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.GitHubIntegration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated setup APIs accept stable provider IDs, never caller-supplied ownership or metadata. */
@RestController
public class GitHubConnectionController {
    private static final String ROOT = "/api/v1/organizations/{organization}/integrations/github";
    private final GitHubConnectionService connections;

    public GitHubConnectionController(GitHubConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping("/api/v1/integrations/github/csrf")
    public CsrfDetails csrf(CsrfToken token) {
        return new CsrfDetails(token.getHeaderName(), token.getToken());
    }

    @PostMapping(ROOT + "/installations")
    public GitHubConnectionService.InstallationStart start(@PathVariable String organization, Authentication caller) {
        return connections.start(organization, caller);
    }

    @GetMapping("/api/v1/integrations/github/callback")
    public GitHubIntegration callback(@RequestParam String state,
            @RequestParam("installation_id") long installationId, Authentication caller) {
        return connections.complete(state, installationId, caller);
    }

    @GetMapping(ROOT)
    public List<GitHubIntegration> list(@PathVariable String organization, Authentication caller) {
        return connections.list(organization, caller);
    }

    @GetMapping(ROOT + "/{integration}/available-repositories")
    public List<GitHubRepository> available(@PathVariable String organization, @PathVariable String integration,
            Authentication caller) {
        return connections.available(organization, integration, caller);
    }

    @GetMapping(ROOT + "/{integration}/repositories")
    public List<SelectedRepository> selected(@PathVariable String organization, @PathVariable String integration,
            Authentication caller) {
        return connections.selected(organization, integration, caller);
    }

    @PutMapping(ROOT + "/{integration}/repositories")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void select(@PathVariable String organization, @PathVariable String integration,
            @Valid @RequestBody Selection selection, Authentication caller) {
        connections.select(organization, integration, selection.repositoryIds(), caller);
    }

    @DeleteMapping(ROOT + "/{integration}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String organization, @PathVariable String integration, Authentication caller) {
        connections.disconnect(organization, integration, caller);
    }

    @DeleteMapping(ROOT + "/{integration}/repositories/{repository}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectRepository(@PathVariable String organization, @PathVariable String integration,
            @PathVariable String repository, Authentication caller) {
        connections.disconnectRepository(organization, integration, repository, caller);
    }

    public record Selection(@NotNull @Size(max = 1000) Set<@NotNull @Positive Long> repositoryIds) { }
    public record CsrfDetails(String headerName, String token) { }
}
