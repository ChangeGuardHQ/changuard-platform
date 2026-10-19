package com.changeguard.change.event;

import java.util.Objects;
import org.springframework.util.Assert;

/** Optional attribution on the original merge fact. */
public record Actor(ActorType type, String id) {
    public Actor {
        Objects.requireNonNull(type, "Actor type is required");
        Assert.hasText(id, "Actor ID is required");
    }
}
