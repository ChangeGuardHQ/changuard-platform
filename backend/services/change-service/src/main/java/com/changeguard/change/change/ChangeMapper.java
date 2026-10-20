package com.changeguard.change.change;

import org.springframework.stereotype.Component;

/** Owns conversion into the immutable, database-owned change projection. */
@Component
public class ChangeMapper {
    public ChangeRecord newRecord(MergedChange merge) {
        return new ChangeRecord(merge);
    }

    public com.changeguard.change.api.dto.ChangeResponse response(ChangeRecord change) {
        return new com.changeguard.change.api.dto.ChangeResponse(change.getId(), change.getOrganizationId(), change.getStatus(),
                change.getProvider(), change.getIntegrationId(), change.getRepositoryId(), change.getRepositoryFullName(),
                change.getPullRequestNumber(), change.getTitle(), change.getCommitSha(), change.getSourceBranch(),
                change.getTargetBranch(), actor(change), change.getMergedAt(), change.getReceivedAt(), change.getCreatedAt());
    }

    public com.changeguard.change.api.dto.ChangeTimelineResponse timeline(ChangeRecord change, java.util.List<TimelineFact> facts) {
        // The stored facts retain their original actors and event IDs as the projection evolves.
        var entries = facts.stream().map(fact -> new com.changeguard.change.api.dto.ChangeTimelineResponse.Entry(
                fact.eventId(), fact.type(), fact.occurredAt(), fact.commitSha(), fact.actor())).toList();
        return new com.changeguard.change.api.dto.ChangeTimelineResponse(change.getId(), change.getOrganizationId(), entries);
    }

    private com.changeguard.change.event.Actor actor(ChangeRecord change) {
        return change.getActorType() == null ? null : new com.changeguard.change.event.Actor(
                com.changeguard.change.event.ActorType.valueOf(change.getActorType()), change.getActorId());
    }


    /** Revalidate the semantic contract at the consumer boundary, even when Avro decoding succeeded. */
    public MergedChange fromAvro(com.changeguard.events.code.PullRequestMerged event) {
        com.changeguard.events.CodeEventSchemas.validate(event);
        var payload = event.getPayload();
        var actor = event.getActor();
        var correlation = event.getCorrelation();
        return new MergedChange(event.getEventId(), event.getOrganizationId(),
                new com.changeguard.change.event.EventSource(event.getSource().getProvider(), event.getSource().getIntegrationId()),
                payload.getDeliveryId(), payload.getRepositoryId(), payload.getRepositoryFullName(),
                payload.getPullRequestNumber(), payload.getPullRequestTitle(), payload.getCommitSha(),
                payload.getSourceBranch(), payload.getTargetBranch(),
                actor == null ? null : new com.changeguard.change.event.Actor(
                        com.changeguard.change.event.ActorType.valueOf(actor.getType().name()), actor.getId()),
                event.getOccurredAt(), event.getReceivedAt(), correlation == null ? null : correlation.getTraceId(),
                correlation == null ? null : correlation.getChangeId());
    }

}
