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
import com.changeguard.change.AvroFixtures;
import com.changeguard.events.code.PullRequestMerged;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = KafkaTransportTest.TransportConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@EmbeddedKafka(partitions = 1, topics = "infrastructure-transport",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = "spring.kafka.consumer.properties.schema.registry.url=mock://change-transport-test")
@DirtiesContext
class KafkaTransportTest {

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Test
    void configuredTransportReadsSpecificAvroFromKafka() throws Exception {
        var values = new LinkedBlockingQueue<PullRequestMerged>();
        var container = factory.createContainer("infrastructure-transport");
        container.getContainerProperties().setGroupId("infrastructure-transport-test");
        container.setupMessageListener((MessageListener<Object, Object>) record -> values.add((PullRequestMerged) record.value()));
        try {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);
            var properties = KafkaTestUtils.producerProps(broker);
            properties.put("schema.registry.url", "mock://change-transport-test");
            // Class-based construction lets Kafka configure the Registry-aware serializer itself.
            properties.put("key.serializer", StringSerializer.class);
            properties.put("value.serializer", KafkaAvroSerializer.class);
            var expected = AvroFixtures.merged("transport-1", "org-one");
            try (var producer = new KafkaProducer<String, Object>(properties)) {
                producer.send(new ProducerRecord<>("infrastructure-transport", "repo-wire", expected))
                        .get(10, TimeUnit.SECONDS);
            }
            assertThat(values.poll(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(expected);
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
