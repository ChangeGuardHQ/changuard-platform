package com.changeguard.connector.config;

import java.time.Instant;
import java.util.Map;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaProducerConfig.class, SchemaRegistryConfiguration.class);

    @Test
    void producerHonorsExternalBrokerAndSecurityProperties() {
        contextRunner.withPropertyValues(
                "spring.kafka.bootstrap-servers=kafka.internal:19092",
                "spring.kafka.properties.security.protocol=SASL_SSL",
                "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                "spring.kafka.producer.properties.compression.type=zstd")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ProducerFactory.class)
                            .hasSingleBean(KafkaTemplate.class);
                    ProducerFactory<?, ?> factory = context.getBean(ProducerFactory.class);
                    ProducerConfig settings = new ProducerConfig(factory.getConfigurationProperties());

                    assertThat(settings.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                            .containsExactly("kafka.internal:19092");
                    assertThat(settings.getString("security.protocol")).isEqualTo("SASL_SSL");
                    assertThat(settings.getString("sasl.mechanism")).isEqualTo("SCRAM-SHA-512");
                    assertThat(settings.getString(ProducerConfig.COMPRESSION_TYPE_CONFIG)).isEqualTo("zstd");
                    assertThat(settings.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
                    assertThat(settings.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
                    assertThat(context.getBean(KafkaTemplate.class).getProducerFactory()).isSameAs(factory);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredSerializerWritesRegisteredAvroWithoutJavaTypeHeaders() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, Object> properties = context.getBean(ProducerFactory.class).getConfigurationProperties();
            ProducerConfig settings = new ProducerConfig(properties);
            assertThat(properties.get("auto.register.schemas").toString()).isEqualTo("false");
            assertThat(properties.get("value.subject.name.strategy"))
                    .isEqualTo("io.confluent.kafka.serializers.subject.TopicRecordNameStrategy");
            var registryConfig = new SchemaRegistryConfiguration();
            var registry = registryConfig.schemaRegistryClient(context.getBean(
                    org.springframework.boot.kafka.autoconfigure.KafkaProperties.class));
            registryConfig.registeredCodeEventContracts(registry, "changeguard.code-events");
            try (Serializer<Object> serializer = (Serializer<Object>) settings.getConfiguredInstance(
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Serializer.class);
                 var deserializer = new io.confluent.kafka.serializers.KafkaAvroDeserializer(registry)) {
                serializer.configure(properties, false);
                deserializer.configure(Map.of("schema.registry.url", properties.get("schema.registry.url"),
                        "specific.avro.reader", true), false);
                RecordHeaders headers = new RecordHeaders();
                Instant occurredAt = Instant.parse("2026-10-04T12:00:00Z");
                var merge = new GitHubMergedPullRequest("delivery-1", "9007199254740993", "changeguard/example", 42,
                        "Fix café checkout 🌱", "cccccccccccccccccccccccccccccccccccccccc",
                        "feature/checkout", "main", "release-manager", occurredAt);
                var event = PullRequestMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                        Instant.parse("2026-10-04T12:00:02Z"));
                var record = new com.changeguard.connector.messaging.PullRequestMergedAvroMapper(
                        JsonMapper.builder().build()).toAvro(event);
                byte[] value = serializer.serialize("changeguard.code-events", headers, record);
                assertThat(value[0]).isZero(); // Confluent wire format: magic byte, schema ID, Avro data.
                assertThat(java.nio.ByteBuffer.wrap(value, 1, 4).getInt()).isPositive();
                assertThat(deserializer.deserialize("changeguard.code-events", value)).isEqualTo(record);
                assertThat(headers).isEmpty();
            }
        });
    }
}
