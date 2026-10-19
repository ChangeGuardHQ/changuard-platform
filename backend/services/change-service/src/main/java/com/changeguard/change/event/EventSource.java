package com.changeguard.change.event;

import org.springframework.util.Assert;

/** Trusted organization integration supplied by the producer, never an HTTP query parameter. */
public record EventSource(String provider, String integrationId) {
    public EventSource {
        Assert.hasText(provider, "Provider is required");
        Assert.hasText(integrationId, "Integration ID is required");
    }
}
