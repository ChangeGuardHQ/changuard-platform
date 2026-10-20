package com.changeguard.change.api.dto;

import java.time.Instant;
import java.util.UUID;
import com.changeguard.change.change.ChangeStatus;
import com.changeguard.change.event.Actor;

/** Lifecycle projection; PR/merge fields are nullable for standalone commits or facts not yet observed. */
public record ChangeResponse(UUID id, String organizationId, ChangeStatus status, String provider,
        String integrationId, String repositoryId, String repositoryFullName, Integer pullRequestNumber,
        String title, String commitSha, String sourceBranch, String targetBranch, Actor actor,
        Instant mergedAt, Instant receivedAt, Instant createdAt,
        com.changeguard.change.change.ChangeKind kind, Instant openedAt, String headCommitSha, Instant activityAt) {
}
