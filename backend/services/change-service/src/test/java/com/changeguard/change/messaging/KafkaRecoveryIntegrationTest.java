package com.changeguard.change.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.changeguard.change.AvroFixtures;
import com.changeguard.change.exception.EventProcessingException;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Broker offsets prove retries and failed quarantine never silently acknowledge a source record. */
@SpringJUnitConfig(classes = KafkaRecoveryIntegrationTest.Configuration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@EmbeddedKafka(partitions = 1, topics = {"recovery", "recovery-failure", "recovery-dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers", brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {"spring.kafka.consumer.properties.schema.registry.url=mock://recovery",
        "changeguard.kafka.recovery.dead-letter-topic=recovery-dlt", "changeguard.kafka.recovery.retry-interval=1ms",
        "changeguard.kafka.recovery.retries=2"})
@DirtiesContext
class KafkaRecoveryIntegrationTest {
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired ConcurrentKafkaListenerContainerFactory<Object, Object> factory;
    @MockitoSpyBean KafkaTemplate<byte[], byte[]> template;

    @Test
    void transientFailureRetriesAndInvalidRecordsDoNotBlockLaterRecords() throws Exception {
        var attempts = new AtomicInteger();
        var accepted = new AtomicInteger();
        var container = factory.createContainer("recovery");
        container.getContainerProperties().setGroupId("recovery-test");
        container.setupMessageListener((MessageListener<Object, Object>) record -> {
            if ("temporary".equals(record.key()) && attempts.incrementAndGet() <= 2)
                throw new DataAccessResourceFailureException("Simulated database outage");
            if ("invalid".equals(record.key())) throw new EventProcessingException("Invalid contract");
            accepted.incrementAndGet();
        });
        try (var producer = producer(); var dlt = dltConsumer(); var admin = admin()) {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);
            byte[] valid = avro();
            send(producer, "recovery", "temporary", valid);
            send(producer, "recovery", "invalid", valid);
            send(producer, "recovery", "malformed", new byte[]{1, 2, 3});
            send(producer, "recovery", "next", valid);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(offset(admin, "recovery-test", "recovery")).isEqualTo(4);
                assertThat(accepted.get()).isEqualTo(2);
            });
            assertThat(attempts.get()).isEqualTo(3);
            assertThat(meters.get("changeguard.change.delivery.quarantined").counter().count()).isGreaterThanOrEqualTo(2);
            var records = new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                dlt.poll(Duration.ofMillis(200)).forEach(record -> {
                    var original = record.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_TOPIC);
                    if (original != null && new String(original.value(), java.nio.charset.StandardCharsets.UTF_8).equals("recovery"))
                        records.add(record);
                });
                assertThat(records).hasSize(2);
            });
            assertThat(records.get(0).value()).containsExactly(valid);
            assertThat(records.get(1).value()).containsExactly(1, 2, 3);
            var replay = DeadLetterReplay.replayRecord(records.get(0), "recovery");
            assertThat(replay.value()).containsExactly(valid);
            assertThat(replay.key()).containsExactly(records.get(0).key());
            assertThat(replay.partition()).isZero();
            assertThatThrownBy(() -> DeadLetterReplay.replayRecord(records.get(0), "another-topic"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(container.isRunning()).isTrue();
            String[] replayOptions = {"--bootstrap-servers=" + broker.getBrokersAsString(),
                    "--dead-letter-topic=recovery-dlt", "--source-topic=recovery", "--partition=0",
                    "--offset=" + records.get(0).offset()};
            DeadLetterReplay.main(replayOptions);
            assertThat(offset(admin, "recovery-test", "recovery")).isEqualTo(4);
            var execute = java.util.Arrays.copyOf(replayOptions, replayOptions.length + 1);
            execute[execute.length - 1] = "--execute";
            DeadLetterReplay.main(execute);
            // The invalid fact is quarantined again until its cause is repaired; no group rewind is needed.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(offset(admin, "recovery-test", "recovery")).isEqualTo(5));
        } finally { container.stop(); }
    }

    @Test
    void failedDeadLetterPublishRetainsOffsetUntilRecoveryIsAcknowledged() throws Exception {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")))
                .when(template).send(any(ProducerRecord.class));
        var attempts = new AtomicInteger();
        var container = factory.createContainer("recovery-failure");
        container.getContainerProperties().setGroupId("recovery-failure-test");
        container.setupMessageListener((MessageListener<Object, Object>) record -> {
            attempts.incrementAndGet();
            throw new EventProcessingException("Invalid contract");
        });
        try (var producer = producer(); var admin = admin()) {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);
            send(producer, "recovery-failure", "invalid", avro());
            await().atMost(Duration.ofSeconds(15)).until(() -> attempts.get() >= 2);
            assertThat(offset(admin, "recovery-failure-test", "recovery-failure")).isLessThan(1);
            // Restore the actual broker send; the same source offset can now recover.
            doCallRealMethod().when(template).send(any(ProducerRecord.class));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(offset(admin, "recovery-failure-test", "recovery-failure")).isEqualTo(1));
        } finally { container.stop(); reset(template); }
    }

    private byte[] avro() {
        try (var serializer = new KafkaAvroSerializer()) {
            serializer.configure(Map.of("schema.registry.url", "mock://recovery"), false);
            return serializer.serialize("recovery", AvroFixtures.merged("recovery-event", "org-one"));
        }
    }
    private KafkaProducer<byte[], byte[]> producer() {
        return new KafkaProducer<>(Map.of("bootstrap.servers", broker.getBrokersAsString(),
                "key.serializer", ByteArraySerializer.class, "value.serializer", ByteArraySerializer.class));
    }
    private KafkaConsumer<byte[], byte[]> dltConsumer() {
        var consumer = new KafkaConsumer<byte[], byte[]>(Map.of("bootstrap.servers", broker.getBrokersAsString(),
                "key.deserializer", ByteArrayDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
                "enable.auto.commit", false));
        var partition = new TopicPartition("recovery-dlt", 0);
        consumer.assign(java.util.List.of(partition));
        consumer.seekToBeginning(java.util.List.of(partition));
        return consumer;
    }
    private Admin admin() { return Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString())); }
    private void send(KafkaProducer<byte[], byte[]> producer, String topic, String key, byte[] value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key.getBytes(java.nio.charset.StandardCharsets.UTF_8), value))
                .get(10, TimeUnit.SECONDS);
    }
    private long offset(Admin admin, String group, String topic) throws Exception {
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
                .get(new TopicPartition(topic, 0));
        return committed == null ? -1 : committed.offset();
    }
    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @Import({KafkaConsumerConfig.class, KafkaErrorHandlerConfig.class,
            com.changeguard.change.observability.ProjectionMetrics.class})
    static class Configuration {
        // A real registry checks that the container invokes the configured retry observer.
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry meters() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }
}
