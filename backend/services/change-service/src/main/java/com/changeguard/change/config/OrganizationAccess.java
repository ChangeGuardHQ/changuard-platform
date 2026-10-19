package com.changeguard.change.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Bind the authenticated service identity to an organization before any database lookup. */
@Component
public class OrganizationAccess {
    private final ChangeApiProperties properties;
    public OrganizationAccess(ChangeApiProperties properties) { this.properties = properties; }

    public void requireRead(Authentication authentication, String organization) {
        Assert.hasText(organization, "Organization is required");
        boolean allowed = properties.organizations().stream().anyMatch(binding ->
                organization.equals(binding.organizationId()) && binding.readers().contains(authentication.getName()));
        if (!allowed) throw new AccessDeniedException("Organization access denied");
    }
}
