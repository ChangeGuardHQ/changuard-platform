package com.changeguard.change.change;

import org.springframework.stereotype.Component;

/** Converts shared wire facts and service-owned projections without exposing receipt or transport internals. */
@Component
public class ChangeMapper {
    public ChangeRecord newRecord(MergedChange merge) {
        return new ChangeRecord(merge);
    }

    public com.changeguard.change.api.dto.ChangeResponse response(ChangeRecord change) {
        return new com.changeguard.change.api.dto.ChangeResponse(change.getId(), change.getOrganizationId(), change.getStatus(),
                change.getProvider(), change.getIntegrationId(), change.getRepositoryId(), change.getRepositoryFullName(),
                change.getPullRequestNumber(), change.getTitle(), change.getCommitSha(), change.getSourceBranch(),
                change.getTargetBranch(), actor(change), change.getMergedAt(), change.getReceivedAt(), change.getCreatedAt(),
                change.getKind(), change.getOpenedAt(), change.getHeadCommitSha(), change.getActivityAt());
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

    /** Maps only the other two reviewed contracts, after the same semantic validation as merges. */
    public LifecycleChange fromLifecycle(org.apache.avro.generic.IndexedRecord event) {
        com.changeguard.events.CodeEventSchemas.validate(event);
        boolean opened = event instanceof com.changeguard.events.code.PullRequestOpened;
        if (!opened && !(event instanceof com.changeguard.events.code.CommitCreated))
            throw new IllegalArgumentException("Unsupported lifecycle contract");
        var source = (com.changeguard.events.common.EventSource) field(event, "source");
        var actor = (com.changeguard.events.common.EventActor) field(event, "actor");
        var correlation = (com.changeguard.events.common.EventCorrelation) field(event, "correlation");
        var payload = (org.apache.avro.generic.IndexedRecord) field(event, "payload");
        return new LifecycleChange(opened ? LifecycleChange.Type.PullRequestOpened : LifecycleChange.Type.CommitCreated,
                (String) field(event, "eventId"), (String) field(event, "organizationId"),
                new com.changeguard.change.event.EventSource(source.getProvider(), source.getIntegrationId()),
                (String) field(payload, "deliveryId"), (String) field(payload, "repositoryId"),
                (String) field(payload, "repositoryFullName"), opened ? (Integer) field(payload, "pullRequestNumber") : null,
                (String) field(payload, opened ? "pullRequestTitle" : "commitMessage"),
                (String) field(payload, opened ? "headCommitSha" : "commitSha"),
                (String) field(payload, opened ? "sourceBranch" : "branch"), opened ? (String) field(payload, "targetBranch") : null,
                actor == null ? null : new com.changeguard.change.event.Actor(
                        com.changeguard.change.event.ActorType.valueOf(actor.getType().name()), actor.getId()),
                (java.time.Instant) field(event, "occurredAt"), (java.time.Instant) field(event, "receivedAt"),
                correlation == null ? null : correlation.getTraceId(), correlation == null ? null : correlation.getChangeId());
    }

    private Object field(org.apache.avro.generic.IndexedRecord record, String name) {
        return record.get(record.getSchema().getField(name).pos());
    }

}
