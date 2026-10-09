package com.changeguard.connector.messaging;

import java.time.Instant;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PullRequestMergedAvroMapperTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PullRequestMergedAvroMapper avro = new PullRequestMergedAvroMapper(json);

    @Test
    void upcastsQueuedLegacyTypeWithoutChangingItsIdentityOrStoredEnvelope() {
        var event = event();
        ObjectNode stored = json.valueToTree(event);
        stored.put("eventType", "CodeChangeMerged");
        var original = stored.deepCopy();
        var record = avro.fromOutbox(stored);
        assertThat(record.getEventType()).isEqualTo("PullRequestMerged");
        assertThat(record.getEventVersion()).isEqualTo(1);
        assertThat(record.getEventId()).isEqualTo(event.eventId());
        assertThat(record.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(record.getReceivedAt()).isEqualTo(event.receivedAt());
        assertThat(record.getOrganizationId()).isEqualTo(event.organizationId());
        assertThat(record.getPayload().getDeliveryId()).isEqualTo(event.payload().deliveryId());
        assertThat(stored).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CommitCreated", "PullRequestOpened", "UnknownEvent"})
    void refusesOtherEventTypesRatherThanUpcastingUnrelatedActivity(String type) {
        ObjectNode stored = json.valueToTree(event());
        stored.put("eventType", type);
        assertThatThrownBy(() -> avro.fromOutbox(stored)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesUnsupportedVersionsAndMissingRequiredMergeData() {
        ObjectNode future = json.valueToTree(event());
        future.put("eventVersion", 2);
        assertThatThrownBy(() -> avro.fromOutbox(future)).isInstanceOf(RuntimeException.class);
        ObjectNode incomplete = json.valueToTree(event());
        ((ObjectNode) incomplete.get("payload")).remove("repositoryId");
        assertThatThrownBy(() -> avro.fromOutbox(incomplete)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mapsOptionalActorCorrelationAndTitleWithoutInventingValues() {
        var event = event();
        var record = avro.toAvro(event);
        assertThat(record.getActor()).isNull();
        assertThat(record.getCorrelation()).isNull();
        assertThat(record.getPayload().getPullRequestTitle()).isNull();
        assertThat(record.getPayload().getRepositoryId()).isEqualTo("9007199254740993");
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-10-07T17:00:00.123456Z"));
    }

    private PullRequestMergedEvent event() {
        var merge = new GitHubMergedPullRequest("delivery-1", "9007199254740993", "acme/service", 42,
                null, "cccccccccccccccccccccccccccccccccccccccc", "feature", "main", null,
                Instant.parse("2026-10-07T17:00:00.123456789Z"));
        return PullRequestMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                Instant.parse("2026-10-07T17:00:02.987654321Z"));
    }
}
