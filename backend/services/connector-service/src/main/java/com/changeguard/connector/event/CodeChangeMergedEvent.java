package com.changeguard.connector.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Immutable version-one event describing a change merged into a repository.
 * Tenant and integration identifiers must come from trusted integration
 * context. Timestamps are supplied by the caller; receipt time must not replace
 * merge time.
 *
 * @param eventId stable identity used by consumers to detect duplicate
 * deliveries
 * @param eventType the fixed {@code CodeChangeMerged} event type
 * @param eventVersion the supported schema version, currently {@code 1}
 * @param occurredAt the time the provider reports the change was merged
 * @param receivedAt the time this delivery was received by the connector
 * @param organizationId the ChangeGuard organization owning the integration
 * @param source the provider and ChangeGuard integration identity
 * @param actor the merge actor, or null when unavailable
 * @param correlation existing trace/change identifiers, or null when
 * unavailable
 * @param payload the merged change and original delivery identity
 */
public record CodeChangeMergedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Instant receivedAt,
        String organizationId,
        Source source,
        Actor actor,
        Correlation correlation,
        Payload payload) {

    public static final String EVENT_TYPE = "CodeChangeMerged";
    public static final int EVENT_VERSION = 1;

    public CodeChangeMergedEvent {
        Assert.hasText(eventId, "Event ID is required");
        Assert.isTrue(EVENT_TYPE.equals(eventType), "Unsupported merged change event type");
        Assert.isTrue(eventVersion == EVENT_VERSION, "Unsupported merged change event version");
        Assert.notNull(occurredAt, "Merge timestamp is required");
        Assert.notNull(receivedAt, "Receipt timestamp is required");
        Assert.hasText(organizationId, "Organization ID is required");
        Assert.notNull(source, "Event source is required");
        Assert.notNull(payload, "Merged change payload is required");
    }

    /**
     * Wraps a normalized GitHub merge in the internal event envelope. The event
     * ID is deterministic for the organization, integration and delivery, even
     * when a redelivery has a different receipt timestamp. This does not
     * replace durable delivery deduplication.
     *
     * @param pullRequest the verified and normalized GitHub merge
     * @param organizationId the owning ChangeGuard organization
     * @param integrationId the ChangeGuard integration, not a GitHub
     * installation ID
     * @param receivedAt the connector's recorded receipt timestamp
     * @return a version-one event with no invented correlation or actor
     * classification
     * @throws IllegalArgumentException if required merge or envelope data is
     * missing
     */
    public static CodeChangeMergedEvent fromGitHub(
            GitHubMergedPullRequest pullRequest,
            String organizationId,
            String integrationId,
            Instant receivedAt) {
        Assert.notNull(pullRequest, "Merged pull request is required");
        Assert.hasText(organizationId, "Organization ID is required");
        Source source = new Source("github", integrationId);
        Payload payload = new Payload(pullRequest.deliveryId(), pullRequest.repositoryId(),
                pullRequest.repositoryFullName(), pullRequest.pullRequestNumber(),
                pullRequest.pullRequestTitle(), pullRequest.commitSha(),
                pullRequest.sourceBranch(), pullRequest.targetBranch());
        Actor actor = StringUtils.hasText(pullRequest.actorLogin())
                ? new Actor(ActorType.UNKNOWN, pullRequest.actorLogin()) : null;

        // Length prefixes keep distinct identity components unambiguous.
        String identity = EVENT_TYPE + ":github:"
                + identityPart(organizationId) + identityPart(integrationId)
                + identityPart(pullRequest.deliveryId());
        String eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        return new CodeChangeMergedEvent(eventId, EVENT_TYPE, EVENT_VERSION, pullRequest.mergedAt(), receivedAt,
                organizationId, source, actor, null, payload);
    }

    private static String identityPart(String value) {
        return value.length() + ":" + value;
    }

    /** Provider identity and the internal integration through which the event arrived. */
    public record Source(String provider, String integrationId) {
        public Source {
            Assert.hasText(provider, "Source provider is required");
            Assert.hasText(integrationId, "Integration ID is required");
        }
    }

    /** Provider actor identity; a GitHub login is preserved without guessing its type. */
    public record Actor(ActorType type, String id) {
        public Actor {
            Assert.notNull(type, "Actor type is required");
            Assert.hasText(id, "Actor ID is required");
        }
    }

    public enum ActorType {
        HUMAN, BOT, AI, UNKNOWN
    }

    /** Optional identifiers assigned by tracing and downstream change correlation. */
    public record Correlation(String traceId, String changeId) {
    }

    /** Merge details, including the full commit SHA and the provider's delivery ID. */
    public record Payload(
            String deliveryId,
            String repositoryId,
            String repositoryFullName,
            Integer pullRequestNumber,
            String pullRequestTitle,
            String commitSha,
            String sourceBranch,
            String targetBranch) {
        public Payload {
            Assert.hasText(deliveryId, "Delivery ID is required");
            Assert.hasText(repositoryId, "Repository ID is required");
            Assert.hasText(repositoryFullName, "Repository full name is required");
            Assert.isTrue(pullRequestNumber != null && pullRequestNumber > 0,
                    "Pull request number must be positive");
            Assert.hasText(commitSha, "Merge commit SHA is required");
            Assert.hasText(sourceBranch, "Source branch is required");
            Assert.hasText(targetBranch, "Target branch is required");
        }
    }
}
