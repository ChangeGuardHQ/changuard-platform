package com.changeguard.change.change;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import com.changeguard.change.event.Actor;
import com.changeguard.change.event.EventSource;
import org.springframework.util.Assert;

/** Immutable opened-PR or standalone-commit input; no inferred PR linkage from commit SHA or branch. */
public record LifecycleChange(Type type, String eventId, String organizationId, EventSource source,
        String deliveryId, String repositoryId, String repositoryFullName, Integer pullRequestNumber,
        String title, String commitSha, String sourceBranch, String targetBranch, Actor actor,
        Instant occurredAt, Instant receivedAt, String traceId, String upstreamChangeId) implements ReceiptEvent {
    public enum Type { PullRequestOpened, CommitCreated }

    public LifecycleChange {
        Objects.requireNonNull(type, "Lifecycle type is required");
        Objects.requireNonNull(source, "Source is required");
        for (String text : Arrays.asList(eventId, organizationId, deliveryId, repositoryId, repositoryFullName, commitSha))
            Assert.hasText(text, "Lifecycle identity and repository fields are required");
        if (type == Type.PullRequestOpened) {
            Assert.isTrue(pullRequestNumber != null && pullRequestNumber > 0, "Pull request number must be positive");
            Assert.hasText(sourceBranch, "Source branch is required");
            Assert.hasText(targetBranch, "Target branch is required");
        } else {
            Assert.isTrue(pullRequestNumber == null && targetBranch == null, "Standalone commits cannot assert a PR identity");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "Occurrence time is required").truncatedTo(ChronoUnit.MICROS);
        receivedAt = Objects.requireNonNull(receivedAt, "Receipt time is required").truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : Arrays.asList(type.name(), eventId, organizationId, source.provider(), source.integrationId(),
                    deliveryId, repositoryId, repositoryFullName, Objects.toString(pullRequestNumber, null), title,
                    commitSha, sourceBranch, targetBranch, actor == null ? null : actor.type().name(),
                    actor == null ? null : actor.id(), occurredAt.toString(), traceId, upstreamChangeId)) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                // Type and null-safe length prefixes prevent ambiguous cross-lifecycle replay identities.
                digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
