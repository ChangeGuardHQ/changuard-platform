package com.changeguard.connector.messaging;

import java.util.concurrent.CompletableFuture;

import com.changeguard.connector.event.CodeChangeMergedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Publishes canonical merged change events to the configured code-events topic.
 * Repository IDs are used as keys so events for a repository share a partition.
 */
@Component
public class CodeEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String codeEventsTopic;

    /**
     * Creates a publisher using the service's Kafka infrastructure.
     *
     * @param kafkaTemplate the template configured for String keys and JSON values
     * @param codeEventsTopic the destination topic configured by the application
     */
    public CodeEventProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${changeguard.kafka.topics.code-events}") String codeEventsTopic) {
        Assert.notNull(kafkaTemplate, "Kafka template is required");
        Assert.hasText(codeEventsTopic, "Code events topic must not be blank");
        this.kafkaTemplate = kafkaTemplate;
        this.codeEventsTopic = codeEventsTopic;
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
    public CompletableFuture<SendResult<String, Object>> publish(CodeChangeMergedEvent event) {
        Assert.notNull(event, "Code event is required");
        return kafkaTemplate.send(codeEventsTopic, event.payload().repositoryId(), event);
    }
}
