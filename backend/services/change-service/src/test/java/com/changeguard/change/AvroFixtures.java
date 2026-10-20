package com.changeguard.change;

import com.changeguard.events.code.PullRequestMerged;
import com.changeguard.events.code.PullRequestMergedPayload;
import com.changeguard.events.common.EventSource;

/** Build the same shared record shape emitted by the connector, without inventing a second wire schema. */
public final class AvroFixtures {
    private AvroFixtures() { }
    public static PullRequestMerged merged(String eventId, String organization) {
        var fact = MergeFixtures.merged(eventId, organization, "repo-wire", 7);
        var event = new PullRequestMerged();
        event.setEventId(fact.eventId());
        event.setEventType("PullRequestMerged");
        event.setEventVersion(1);
        event.setOrganizationId(fact.organizationId());
        event.setOccurredAt(fact.mergedAt());
        event.setReceivedAt(fact.receivedAt());
        event.setSource(new EventSource(fact.source().provider(), fact.source().integrationId()));
        event.setPayload(new PullRequestMergedPayload(fact.deliveryId(), fact.repositoryId(), fact.repositoryFullName(),
                fact.pullRequestNumber(), fact.title(), fact.commitSha(), fact.sourceBranch(), fact.targetBranch()));
        return event;
    }
    public static com.changeguard.events.code.PullRequestOpened opened(String eventId, String organization) {
        var event = new com.changeguard.events.code.PullRequestOpened();
        envelope(event, eventId, organization);
        event.setOccurredAt(MergeFixtures.MERGED_AT.minusSeconds(3600));
        event.setPayload(new com.changeguard.events.code.PullRequestOpenedPayload(eventId, "repo-wire", "acme/service", 7,
                "Original PR title", "b".repeat(40), "feature", "main"));
        return event;
    }

    public static com.changeguard.events.code.CommitCreated commit(String eventId, String organization) {
        var event = new com.changeguard.events.code.CommitCreated();
        envelope(event, eventId, organization);
        event.setOccurredAt(MergeFixtures.MERGED_AT.minusSeconds(7200));
        event.setPayload(new com.changeguard.events.code.CommitCreatedPayload(eventId, "repo-wire", "acme/service",
                MergeFixtures.SHA, "Commit message", "main"));
        return event;
    }

    private static void envelope(org.apache.avro.specific.SpecificRecordBase record, String id, String organization) {
        // Fixtures retain the canonical discriminator and generated specific record for each contract.
        record.put("eventId", id);
        record.put("eventType", record.getSchema().getName());
        record.put("eventVersion", 1);
        record.put("organizationId", organization);
        record.put("receivedAt", MergeFixtures.MERGED_AT.plusSeconds(1));
        record.put("source", new EventSource("github", "integration"));
    }

}
