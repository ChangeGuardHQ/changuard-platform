package com.changeguard.connector.messaging;

import java.util.concurrent.CompletableFuture;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.connector.persistence.OutboxEventRepository.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;

/**
 * Publishes canonical code lifecycle events to the configured code-events topic.
 * Repository IDs are used as keys so events for a repository share a partition.
 */
@Component
public class CodeEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String codeEventsTopic;
    private final PullRequestMergedAvroMapper avro;

    /**
     * Creates a publisher using the service's Kafka infrastructure.
     *
     * @param kafkaTemplate the template configured for String keys and registered Avro values
     * @param codeEventsTopic the destination topic configured by the application
     */
    public CodeEventProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${changeguard.kafka.topics.code-events}") String codeEventsTopic,
            PullRequestMergedAvroMapper avro) {
        Assert.notNull(kafkaTemplate, "Kafka template is required");
        Assert.hasText(codeEventsTopic, "Code events topic must not be blank");
        Assert.notNull(avro, "Avro mapper is required");
        this.kafkaTemplate = kafkaTemplate;
        this.codeEventsTopic = codeEventsTopic;
        this.avro = avro;
    }

    /**
     * Sends an event using its repository ID as the Kafka key.
     * The caller must observe the returned future before treating publication as
     * acknowledged. Kafka send errors raised before a future is returned propagate
     * directly; later errors complete the future exceptionally.
     *
     * @param event the canonical merged change event to publish
     * @return the send future carrying broker acknowledgement metadata or a failure
     * @throws IllegalArgumentException if the event is null
     */
    public CompletableFuture<SendResult<String, Object>> publish(PullRequestMergedEvent event) {
        Assert.notNull(event, "Code event is required");
        return kafkaTemplate.send(codeEventsTopic, event.payload().repositoryId(), avro.toAvro(event));
    }

    /** Uses the recorded destination/key and maps the durable envelope to registered Avro. */
    public CompletableFuture<SendResult<String, Object>> publish(OutboxEvent event, JsonNode payload) {
        Assert.notNull(event, "Outbox event is required");
        Assert.isTrue(payload != null && payload.isObject(), "Outbox payload must be a JSON object");
        var record = avro.fromAnyOutbox(payload);
        var source = (com.changeguard.events.common.EventSource) record.get("source");
        var body = (org.apache.avro.generic.IndexedRecord) record.get("payload");
        String repository = (String) body.get(body.getSchema().getField("repositoryId").pos());
        Assert.isTrue(event.eventId().equals(record.get("eventId"))
                && event.organizationId().equals(record.get("organizationId"))
                && event.integrationId().equals(source.getIntegrationId())
                && event.eventType().equals(payload.path("eventType").asString())
                && Integer.valueOf(event.eventVersion()).equals(record.get("eventVersion"))
                && event.partitionKey().equals(repository),
                "Outbox metadata must match the canonical event");
        // Pre-contract rows recorded the JSON destination. Send their canonical
        // upcast to the configured Avro topic without rewriting the durable row.
        String topic = "CodeChangeMerged".equals(event.eventType()) ? codeEventsTopic : event.topic();
        return kafkaTemplate.send(topic, event.partitionKey(), record);
    }
}
