package com.changeguard.connector.event;

import java.time.Instant;

import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeChangeMergedEventTests {

    private static final Instant MERGED_AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-04T12:00:02Z");

    @Test
    void keepsIdentityStableAcrossRedeliveryWhilePreservingBothTimestamps() {
        var merge = merge("delivery-1", "release-manager", "Fix checkout", MERGED_AT);
        var first = CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1", RECEIVED_AT);
        var repeated = CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                RECEIVED_AT.plusSeconds(60));

        assertThat(first.eventId()).isEqualTo("c2120429-d5d9-317f-8364-3df214dd2242");
        assertThat(repeated.eventId()).isEqualTo(first.eventId());
        assertThat(repeated.occurredAt()).isEqualTo(MERGED_AT);
        assertThat(repeated.receivedAt()).isEqualTo(RECEIVED_AT.plusSeconds(60));
        assertThat(repeated.payload()).isEqualTo(first.payload());
    }

    @Test
    void scopesIdentityToOrganizationIntegrationAndDelivery() {
        var merge = merge("delivery-1", "actor", "Title", MERGED_AT);
        String eventId = CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1", RECEIVED_AT).eventId();

        assertThat(CodeChangeMergedEvent.fromGitHub(merge, "org-2", "integration-1", RECEIVED_AT).eventId())
                .isNotEqualTo(eventId);
        assertThat(CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-2", RECEIVED_AT).eventId())
                .isNotEqualTo(eventId);
        assertThat(CodeChangeMergedEvent.fromGitHub(
                merge("delivery-2", "actor", "Title", MERGED_AT), "org-1", "integration-1", RECEIVED_AT).eventId())
                .isNotEqualTo(eventId);
    }

    @Test
    void doesNotCollideWhenIdentityComponentsContainSeparators() {
        var merge = merge("delivery-1", "actor", "Title", MERGED_AT);
        var first = CodeChangeMergedEvent.fromGitHub(merge, "org:integration", "1", RECEIVED_AT);
        var second = CodeChangeMergedEvent.fromGitHub(merge, "org", "integration:1", RECEIVED_AT);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void preservesOptionalDataAndDoesNotInventActorClassificationOrCorrelation() {
        var event = CodeChangeMergedEvent.fromGitHub(
                merge("delivery-1", "automation-bot", null, MERGED_AT), "org-1", "integration-1", RECEIVED_AT);

        assertThat(event.actor()).isEqualTo(new CodeChangeMergedEvent.Actor(
                CodeChangeMergedEvent.ActorType.UNKNOWN, "automation-bot"));
        assertThat(event.correlation()).isNull();
        assertThat(event.payload().pullRequestTitle()).isNull();
        assertThat(CodeChangeMergedEvent.fromGitHub(
                merge("delivery-1", null, null, MERGED_AT), "org-1", "integration-1", RECEIVED_AT).actor()).isNull();
    }

    @Test
    void requiresTrustedTenantContextAndRealTimestamps() {
        var merge = merge("delivery-1", "actor", "Title", MERGED_AT);

        assertThatThrownBy(() -> CodeChangeMergedEvent.fromGitHub(merge, " ", "integration-1", RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Organization ID is required");
        assertThatThrownBy(() -> CodeChangeMergedEvent.fromGitHub(merge, "org-1", null, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Integration ID is required");
        assertThatThrownBy(() -> CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Receipt timestamp is required");
        assertThatThrownBy(() -> CodeChangeMergedEvent.fromGitHub(
                merge("delivery-1", "actor", "Title", null), "org-1", "integration-1", RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Merge timestamp is required");
        assertThatThrownBy(() -> CodeChangeMergedEvent.fromGitHub(null, "org-1", "integration-1", RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Merged pull request is required");
    }

    @Test
    void roundTripsExistingCorrelationAlongsideOptionalActorData() {
        var event = CodeChangeMergedEvent.fromGitHub(
                merge("delivery-1", null, null, MERGED_AT), "org-1", "integration-1", RECEIVED_AT);
        var correlated = new CodeChangeMergedEvent(event.eventId(), event.eventType(), event.eventVersion(),
                event.occurredAt(), event.receivedAt(),
                event.organizationId(), event.source(), event.actor(),
                new CodeChangeMergedEvent.Correlation("trace-1", "change-1"), event.payload());
        var mapper = JsonMapper.builder().build();

        var decoded = mapper.readValue(mapper.writeValueAsBytes(correlated), CodeChangeMergedEvent.class);

        assertThat(decoded).isEqualTo(correlated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventType", "eventVersion"})
    void rejectsUnsupportedWireTypesAndVersions(String field) {
        var event = CodeChangeMergedEvent.fromGitHub(
                merge("delivery-1", "actor", "Title", MERGED_AT), "org-1", "integration-1", RECEIVED_AT);
        var mapper = JsonMapper.builder().build();
        var json = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(event));
        if (field.equals("eventType")) {
            json.put(field, "DeploymentCompleted");
        } else {
            json.put(field, 2);
        }

        assertThatThrownBy(() -> mapper.readValue(mapper.writeValueAsBytes(json), CodeChangeMergedEvent.class))
                .isInstanceOf(JacksonException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static GitHubMergedPullRequest merge(String deliveryId, String actor, String title, Instant mergedAt) {
        return new GitHubMergedPullRequest(deliveryId, "12345", "changeguard/example", 42, title,
                "cccccccccccccccccccccccccccccccccccccccc", "feature/checkout", "main", actor, mergedAt);
    }
}
