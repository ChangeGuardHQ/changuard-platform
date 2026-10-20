package com.changeguard.connector.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.util.Assert;

/** Durable JSON outbox envelope for opening and commit facts; ownership comes from trusted installation storage. */
public record CodeLifecycleEvent(String eventId, String eventType, int eventVersion, Instant occurredAt,
        Instant receivedAt, String organizationId, PullRequestMergedEvent.Source source,
        PullRequestMergedEvent.Actor actor, PullRequestMergedEvent.Correlation correlation, Payload payload) {
    public CodeLifecycleEvent {
        Assert.hasText(eventId, "Event ID is required");
        Assert.isTrue("PullRequestOpened".equals(eventType) || "CommitCreated".equals(eventType), "Unsupported lifecycle type");
        Assert.isTrue(eventVersion == 1, "Unsupported lifecycle version");
        Assert.hasText(organizationId, "Organization is required");
        Assert.notNull(source, "Source is required");
        Assert.notNull(payload, "Payload is required");
        Assert.notNull(occurredAt, "Occurrence time is required");
        Assert.notNull(receivedAt, "Receipt time is required");
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        receivedAt = receivedAt.truncatedTo(ChronoUnit.MICROS);
        if ("PullRequestOpened".equals(eventType)) {
            Assert.isTrue(payload.pullRequestNumber() != null && payload.pullRequestNumber() > 0, "PR number is required");
            Assert.hasText(payload.headCommitSha(), "Head SHA is required");
            Assert.hasText(payload.sourceBranch(), "Source branch is required");
            Assert.hasText(payload.targetBranch(), "Target branch is required");
        } else Assert.hasText(payload.commitSha(), "Commit SHA is required");
    }

    public static CodeLifecycleEvent fromGitHub(String type, Instant occurred, Instant received, String organization,
            String integration, String actorLogin, Payload payload) {
        Assert.hasText(organization, "Organization is required");
        Assert.hasText(integration, "Integration is required");
        Assert.notNull(payload, "Payload is required");
        // A push has one delivery but several commits; SHA is part of each commit's deterministic event ID.
        String identity = "ChangeGuard:github:" + part(type) + part(organization) + part(integration)
                + part(payload.deliveryId()) + part("CommitCreated".equals(type) ? payload.commitSha() : "");
        return new CodeLifecycleEvent(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                type, 1, occurred, received, organization, new PullRequestMergedEvent.Source("github", integration),
                actorLogin == null ? null : new PullRequestMergedEvent.Actor(PullRequestMergedEvent.ActorType.UNKNOWN, actorLogin),
                null, payload);
    }

    private static String part(String text) { return text.length() + ":" + text; }

    /** Nullable fields belong to the other lifecycle variant and are excluded from its Avro payload. */
    public record Payload(String deliveryId, String repositoryId, String repositoryFullName, Integer pullRequestNumber,
            String pullRequestTitle, String headCommitSha, String sourceBranch, String targetBranch,
            String commitSha, String commitMessage, String branch) {
        public Payload {
            Assert.hasText(deliveryId, "Delivery is required");
            Assert.hasText(repositoryId, "Repository is required");
            Assert.hasText(repositoryFullName, "Repository name is required");
        }
    }
}
