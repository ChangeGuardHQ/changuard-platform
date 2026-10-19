package com.changeguard.change.config;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/** Deployment-owned bindings; an HTTP-supplied organization is never authorization by itself. */
@ConfigurationProperties("changeguard.api")
public record ChangeApiProperties(List<OrganizationReaders> organizations) {
    public ChangeApiProperties {
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
    }

    public record OrganizationReaders(String organizationId, Set<String> readers) {
        public OrganizationReaders {
            readers = readers == null ? Set.of() : Set.copyOf(readers);
            // Empty defaults grant no access. Partially configured bindings fail at startup.
            if (organizationId != null && !organizationId.isBlank() || !readers.isEmpty()) {
                Assert.hasText(organizationId, "An organization is required for configured readers");
                Assert.isTrue(!readers.isEmpty() && readers.stream().allMatch(name -> name != null && !name.isBlank()),
                        "Organization readers must be nonblank");
            }
        }
    }
}
