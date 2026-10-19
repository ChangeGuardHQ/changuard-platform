package com.changeguard.change.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.changeguard.change.event.Actor;

/** Timeline entries represent lifecycle facts, never individual transport redeliveries. */
public record ChangeTimelineResponse(UUID changeId, String organizationId, List<Entry> entries) {
    public record Entry(String eventId, String type, Instant occurredAt, String commitSha, Actor actor) { }
}
