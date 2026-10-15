package com.changeguard.change.messaging;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = KafkaTransportTest.TransportConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@EmbeddedKafka(partitions = 1, topics = "infrastructure-transport",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class KafkaTransportTest {

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Test
    void configuredTransportReadsHeaderlessJsonFromKafka() throws Exception {
        var values = new LinkedBlockingQueue<JsonNode>();
        var container = factory.createContainer("infrastructure-transport");
        container.getContainerProperties().setGroupId("infrastructure-transport-test");
        container.setupMessageListener((MessageListener<Object, Object>) record -> values.add((JsonNode) record.value()));
        try {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);
            try (var producer = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                    new StringSerializer(), new StringSerializer())) {
                producer.send(new ProducerRecord<>("infrastructure-transport", "org-1", """
                        {"eventId":"transport-1","payload":{"repositoryId":"repo-1"}}
                        """)).get(10, TimeUnit.SECONDS);
            }
            var result = values.poll(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            assertThat(result).isNotNull();
            assertThat(result.path("eventId").asString()).isEqualTo("transport-1");
            assertThat(result.path("payload").path("repositoryId").asString()).isEqualTo("repo-1");
        } finally {
            container.stop();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @Import({KafkaConsumerConfig.class, KafkaErrorHandlerConfig.class})
    static class TransportConfiguration {
    }
}
