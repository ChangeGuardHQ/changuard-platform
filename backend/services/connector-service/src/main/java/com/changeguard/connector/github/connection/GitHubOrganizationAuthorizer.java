package com.changeguard.connector.github.connection;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GitHubOrganizationAuthorizer {
    private final GitHubConnectionProperties properties;

    public GitHubOrganizationAuthorizer(GitHubConnectionProperties properties) {
        this.properties = properties;
    }

    public GitHubConnectionProperties.OrganizationBinding requireAdministrator(String organization,
            Authentication authentication) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub connection is not configured");
        }
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return properties.organizations().stream()
                .filter(binding -> binding.organizationId().equals(organization)
                        && binding.administrators().contains(authentication.getName()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Organization administrator access is required"));
    }
}
