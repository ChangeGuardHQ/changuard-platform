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
}
