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
 * Maps durable JSON outbox envelopes into the three shared code-event contracts.
 * The merge-specific entry points retain support for historical stored envelopes.
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
    /** Preserve the legacy merge upcast while dispatching newly supported durable lifecycle envelopes. */
    public org.apache.avro.specific.SpecificRecordBase fromAnyOutbox(JsonNode payload) {
        String type = payload == null ? "" : payload.path("eventType").asString();
        if (!"PullRequestOpened".equals(type) && !"CommitCreated".equals(type)) return fromOutbox(payload);
        var event = json.treeToValue(payload, com.changeguard.connector.event.CodeLifecycleEvent.class);
        var p = event.payload();
        org.apache.avro.specific.SpecificRecordBase record;
        if ("PullRequestOpened".equals(type)) {
            var opened = new com.changeguard.events.code.PullRequestOpened();
            opened.setPayload(new com.changeguard.events.code.PullRequestOpenedPayload(p.deliveryId(), p.repositoryId(),
                    p.repositoryFullName(), p.pullRequestNumber(), p.pullRequestTitle(), p.headCommitSha(), p.sourceBranch(), p.targetBranch()));
            record = opened;
        } else {
            var commit = new com.changeguard.events.code.CommitCreated();
            commit.setPayload(new com.changeguard.events.code.CommitCreatedPayload(p.deliveryId(), p.repositoryId(),
                    p.repositoryFullName(), p.commitSha(), p.commitMessage(), p.branch()));
            record = commit;
        }
        record.put("eventId", event.eventId());
        record.put("eventType", event.eventType());
        record.put("eventVersion", event.eventVersion());
        record.put("organizationId", event.organizationId());
        record.put("occurredAt", event.occurredAt());
        record.put("receivedAt", event.receivedAt());
        record.put("source", new EventSource(event.source().provider(), event.source().integrationId()));
        record.put("actor", event.actor() == null ? null : new EventActor(ActorType.valueOf(event.actor().type().name()), event.actor().id()));
        record.put("correlation", event.correlation() == null ? null : new EventCorrelation(event.correlation().traceId(), event.correlation().changeId()));
        com.changeguard.events.CodeEventSchemas.validate(record);
        return record;
    }

}
