package com.changeguard.connector.normalization;

import java.time.Instant;
import com.changeguard.connector.LifecycleWebhookFixtures;
import com.changeguard.connector.github.InvalidGitHubPayloadException;
import com.changeguard.connector.messaging.PullRequestMergedAvroMapper;
import com.changeguard.events.code.CommitCreated;
import com.changeguard.events.code.PullRequestOpened;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

/** Keep provider timestamps and deterministic per-commit IDs while rejecting coercion and malformed facts. */
class GitHubCodeLifecycleNormalizerTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final GitHubCodeLifecycleNormalizer normalizer = new GitHubCodeLifecycleNormalizer(json);
    private final Instant received = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void openingMapsToTheSharedContractWithTrustedOwnershipAndProviderCreationTime() {
        var event = normalizer.normalize("pull_request", "open", LifecycleWebhookFixtures.opened()).orElseThrow()
                .events("trusted-org", "integration", received).getFirst();
        var wire = new PullRequestMergedAvroMapper(json).fromAnyOutbox(json.valueToTree(event));
        assertThat(wire).isInstanceOf(PullRequestOpened.class);
        assertThat(wire.get("organizationId")).isEqualTo("trusted-org");
        assertThat(wire.get("occurredAt")).isEqualTo(Instant.parse("2026-10-04T10:00:00Z"));
    }

    @Test
    void pushHasStableDistinctEventIdsPerCommitEvenAfterRedeliveryAndReordering() {
        var first = normalizer.normalize("push", "push", LifecycleWebhookFixtures.push()).orElseThrow()
                .events("org", "integration", received);
        var replay = normalizer.normalize("push", "push", LifecycleWebhookFixtures.push()).orElseThrow()
                .events("org", "integration", received.plusSeconds(10));
        assertThat(first).hasSize(2).extracting(event -> event.eventId()).doesNotHaveDuplicates();
        assertThat(replay).extracting(event -> event.eventId()).containsExactlyElementsOf(first.stream().map(event -> event.eventId()).toList());
        for (var event : first) assertThat(new PullRequestMergedAvroMapper(json).fromAnyOutbox(json.valueToTree(event)))
                .isInstanceOf(CommitCreated.class);
        var root = (ObjectNode) json.readTree(LifecycleWebhookFixtures.push());
        var commits = root.withArray("commits");
        var original = commits.get(0).deepCopy();
        commits.set(0, commits.get(1));
        commits.set(1, original);
        var reordered = normalizer.normalize("push", "push", json.writeValueAsBytes(root)).orElseThrow()
                .events("org", "integration", received);
        assertThat(reordered).extracting(event -> event.eventId()).containsExactly(first.get(1).eventId(), first.get(0).eventId());
    }

    @Test
    void installationCoercionInvalidShaAndDuplicateJsonFieldsAreRejected() {
        var root = (ObjectNode) json.readTree(LifecycleWebhookFixtures.push());
        root.withObject("installation").put("id", "123");
        assertThatThrownBy(() -> normalizer.normalize("push", "delivery", json.writeValueAsBytes(root)))
                .isInstanceOf(InvalidGitHubPayloadException.class);
        root.withObject("installation").put("id", 123);
        ((ObjectNode) root.path("commits").get(0)).put("id", "truncated-sha");
        assertThatThrownBy(() -> normalizer.normalize("push", "delivery", json.writeValueAsBytes(root)))
                .isInstanceOf(InvalidGitHubPayloadException.class);
        assertThatThrownBy(() -> normalizer.normalize("push", "delivery", "{\"ref\":\"a\",\"ref\":\"b\"}".getBytes()))
                .isInstanceOf(InvalidGitHubPayloadException.class);
    }

    @Test
    void deletionAndTagPushesProduceNoCommitCreationFacts() {
        var root = (ObjectNode) json.readTree(LifecycleWebhookFixtures.push());
        root.put("deleted", true);
        assertThat(normalizer.normalize("push", "delivery", json.writeValueAsBytes(root))).isEmpty();
        root.put("deleted", false).put("ref", "refs/tags/release");
        assertThat(normalizer.normalize("push", "delivery", json.writeValueAsBytes(root))).isEmpty();
    }
}
