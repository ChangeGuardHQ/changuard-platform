package com.changeguard.change.change;

import java.time.Instant;
import com.changeguard.change.event.Actor;

/** A provider occurrence retained once per business fact, independent of later transport deliveries. */
public record TimelineFact(String eventId, String type, Instant occurredAt, String commitSha, Actor actor) { }
