package com.changeguard.change.api.dto;

import java.time.Instant;
import java.util.UUID;
import com.changeguard.change.change.ChangeStatus;
import com.changeguard.change.event.Actor;

/** Public projection fields; provider payloads and database receipt internals are not exposed. */
public record ChangeResponse(UUID id, String organizationId, ChangeStatus status, String provider,
        String integrationId, String repositoryId, String repositoryFullName, int pullRequestNumber,
        String title, String commitSha, String sourceBranch, String targetBranch, Actor actor,
        Instant mergedAt, Instant receivedAt, Instant createdAt) {
}
