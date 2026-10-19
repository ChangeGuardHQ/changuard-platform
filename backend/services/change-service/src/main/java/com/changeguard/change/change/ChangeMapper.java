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

    public com.changeguard.change.api.dto.ChangeTimelineResponse timeline(ChangeRecord change) {
        var merge = new com.changeguard.change.api.dto.ChangeTimelineResponse.Entry(change.getSourceEventId(),
                "PullRequestMerged", change.getMergedAt(), change.getCommitSha(), actor(change));
        return new com.changeguard.change.api.dto.ChangeTimelineResponse(change.getId(), change.getOrganizationId(), java.util.List.of(merge));
    }

    private com.changeguard.change.event.Actor actor(ChangeRecord change) {
        return change.getActorType() == null ? null : new com.changeguard.change.event.Actor(
                com.changeguard.change.event.ActorType.valueOf(change.getActorType()), change.getActorId());
    }

}
