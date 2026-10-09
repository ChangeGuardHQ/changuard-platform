package com.changeguard.connector.messaging;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.events.code.PullRequestMerged;
import com.changeguard.events.code.PullRequestMergedPayload;
import com.changeguard.events.common.ActorType;
import com.changeguard.events.common.EventActor;
import com.changeguard.events.common.EventCorrelation;
import com.changeguard.events.common.EventSource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts durable JSON into the shared Avro contract without rewriting stored events. */
@Component
public class PullRequestMergedAvroMapper {
    private final JsonMapper json;

    public PullRequestMergedAvroMapper(JsonMapper json) {
        com.changeguard.events.CodeEventSchemas.trustGeneratedClasses();
        this.json = json;
    }

    public PullRequestMerged fromOutbox(JsonNode payload) {
        Assert.isTrue(payload != null && payload.isObject(), "Outbox payload must be a JSON object");
        // Upcast only the original merged-PR discriminator. Unknown types and
        // versions still fail the domain record's validation before Kafka I/O.
        var canonical = payload.deepCopy();
        if ("CodeChangeMerged".equals(payload.path("eventType").asString())) {
            ((tools.jackson.databind.node.ObjectNode) canonical).put("eventType", PullRequestMergedEvent.EVENT_TYPE);
        }
        return toAvro(json.treeToValue(canonical, PullRequestMergedEvent.class));
    }

    public PullRequestMerged toAvro(PullRequestMergedEvent event) {
        Assert.notNull(event, "Code event is required");
        var record = new PullRequestMerged();
        record.setEventId(event.eventId());
        record.setEventType(event.eventType());
        record.setEventVersion(event.eventVersion());
        record.setOccurredAt(event.occurredAt());
        record.setReceivedAt(event.receivedAt());
        record.setOrganizationId(event.organizationId());
        record.setSource(new EventSource(event.source().provider(), event.source().integrationId()));
        if (event.actor() != null) {
            record.setActor(new EventActor(ActorType.valueOf(event.actor().type().name()), event.actor().id()));
        }
        if (event.correlation() != null) {
            record.setCorrelation(new EventCorrelation(event.correlation().traceId(), event.correlation().changeId()));
        }
        var payload = event.payload();
        record.setPayload(new PullRequestMergedPayload(payload.deliveryId(), payload.repositoryId(),
                payload.repositoryFullName(), payload.pullRequestNumber(), payload.pullRequestTitle(),
                payload.commitSha(), payload.sourceBranch(), payload.targetBranch()));
        com.changeguard.events.CodeEventSchemas.validate(record);
        return record;
    }
}
