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

/**
 * Maps the durable JSON representation of a merged pull request event into the
 * shared Avro contract used for Kafka publication.
 *
 * <p>
 * The mapper intentionally preserves the original stored event payload and only
 * normalizes the discriminator needed to match the canonical event type during
 * deserialization. This avoids rewriting already persisted outbox records while
 * still ensuring the generated Avro model is validated before transport.</p>
 */
@Component
public class PullRequestMergedAvroMapper {

    private final JsonMapper json;

    /**
     * Creates the mapper and initializes the generated Avro classes used by the
     * shared event schema registry.
     */
    public PullRequestMergedAvroMapper(JsonMapper json) {
        com.changeguard.events.CodeEventSchemas.trustGeneratedClasses();
        this.json = json;
    }

    /**
     * Converts a raw outbox payload into the canonical Avro event type.
     *
     * @param payload the JSON object stored in the outbox; may include the
     * legacy discriminator used by the original merged-PR event
     * @return the validated Avro record ready to emit to Kafka
     * @throws IllegalArgumentException when the payload is missing or not a
     * JSON object
     */
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

    /**
     * Converts the typed domain event into the shared Avro record used by the
     * event contract.
     *
     * @param event the connector event to translate
     * @return the generated Avro representation; validated against the shared
     * schema
     */
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
