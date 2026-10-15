package com.changeguard.connector.github.connection;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/** Deployment-owned authorization bindings; HTTP callers cannot create or change them. */
@ConfigurationProperties("changeguard.github.connection")
public record GitHubConnectionProperties(boolean enabled, long appId, String appSlug,
        String privateKeyPath, String stateSecret, Duration stateTtl, List<OrganizationBinding> organizations) {

    public GitHubConnectionProperties {
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        stateTtl = stateTtl == null ? Duration.ofMinutes(10) : stateTtl;
        if (enabled) {
            Assert.isTrue(appId > 0, "GitHub App ID must be positive");
            Assert.isTrue(appSlug != null && appSlug.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,99}"),
                    "GitHub App slug is invalid");
            Assert.hasText(privateKeyPath, "GitHub App private key path is required");
            Assert.isTrue(stateSecret != null && stateSecret.getBytes(StandardCharsets.UTF_8).length >= 32,
                    "GitHub callback state secret must contain at least 32 bytes");
            Assert.isTrue(stateTtl.compareTo(Duration.ofMinutes(1)) >= 0
                    && stateTtl.compareTo(Duration.ofMinutes(30)) <= 0, "GitHub state TTL must be 1 to 30 minutes");
            Assert.isTrue(!organizations.isEmpty(), "At least one trusted organization binding is required");
            Set<String> ids = new HashSet<>();
            Set<Long> accounts = new HashSet<>();
            for (var binding : organizations) {
                Assert.hasText(binding.organizationId(), "Trusted organization ID is required");
                Assert.isTrue(binding.githubAccountId() > 0, "Trusted GitHub account ID must be positive");
                Assert.isTrue(binding.administrators() != null && !binding.administrators().isEmpty()
                        && binding.administrators().stream().allMatch(name -> name != null && !name.isBlank()),
                        "Trusted organization administrators are required");
                Assert.isTrue(ids.add(binding.organizationId()) && accounts.add(binding.githubAccountId()),
                        "Trusted organization and GitHub account bindings must be unique");
            }
        }
    }

    @Override
    public String toString() {
        return "GitHubConnectionProperties[enabled=" + enabled + "]";
    }

    public record OrganizationBinding(String organizationId, long githubAccountId, Set<String> administrators) {
        public OrganizationBinding {
            administrators = administrators == null ? Set.of() : Set.copyOf(administrators);
        }
    }
}
