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

/** Immutable application input; the shared Avro record remains the Kafka transport contract. */
public record MergedChange(String eventId, String organizationId, EventSource source,
        String deliveryId, String repositoryId, String repositoryFullName, int pullRequestNumber,
        String title, String commitSha, String sourceBranch, String targetBranch, Actor actor,
        Instant mergedAt, Instant receivedAt, String traceId, String upstreamChangeId) implements ReceiptEvent {

    public MergedChange {
        for (String text : Arrays.asList(eventId, organizationId, deliveryId, repositoryId,
                repositoryFullName, commitSha, sourceBranch, targetBranch)) {
            Assert.hasText(text, "Merge identity and repository fields must be nonblank");
        }
        Objects.requireNonNull(source, "Source is required");
        Assert.isTrue(pullRequestNumber > 0, "Pull request number must be positive");
        // Match Avro and PostgreSQL precision before comparing an event with a persisted fact.
        mergedAt = Objects.requireNonNull(mergedAt, "Merge time is required").truncatedTo(ChronoUnit.MICROS);
        receivedAt = Objects.requireNonNull(receivedAt, "Receipt time is required").truncatedTo(ChronoUnit.MICROS);
    }

    /** Detect reuse of an event ID with changed facts; a redelivery's receipt time is not identity. */
    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : Arrays.asList(eventId, organizationId, source.provider(), source.integrationId(),
                    deliveryId, repositoryId, repositoryFullName, Integer.toString(pullRequestNumber), title,
                    commitSha, sourceBranch, targetBranch, actor == null ? null : actor.type().name(),
                    actor == null ? null : actor.id(), mergedAt.toString(), traceId, upstreamChangeId)) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                // Length prefixes distinguish null, empty values, and separators inside provider strings.
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime must support SHA-256", impossible);
        }
    }
}
