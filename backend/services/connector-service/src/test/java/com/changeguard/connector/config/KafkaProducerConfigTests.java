package com.changeguard.connector.config;

import java.time.Instant;
import java.util.Map;

import com.changeguard.connector.event.CodeChangeMergedEvent;
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
            .withUserConfiguration(KafkaProducerConfig.class);

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
    void configuredSerializerWritesEventJsonWithoutJavaTypeHeaders() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, Object> properties = context.getBean(ProducerFactory.class).getConfigurationProperties();
            ProducerConfig settings = new ProducerConfig(properties);

            try (Serializer<Object> serializer = (Serializer<Object>) settings.getConfiguredInstance(
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Serializer.class)) {
                serializer.configure(properties, false);
                RecordHeaders headers = new RecordHeaders();
                Instant occurredAt = Instant.parse("2026-10-04T12:00:00Z");
                var merge = new GitHubMergedPullRequest("delivery-1", "12345", "changeguard/example", 42,
                        "Fix café checkout 🌱", "cccccccccccccccccccccccccccccccccccccccc",
                        "feature/checkout", "main", "release-manager", occurredAt);
                var event = CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                        Instant.parse("2026-10-04T12:00:02Z"));
                byte[] value = serializer.serialize("changeguard.code-events", headers, event);

                var mapper = JsonMapper.builder().build();
                var json = mapper.readTree(value);
                assertThat(json).isEqualTo(mapper.readTree("""
                        {
                          "eventId": "%s",
                          "eventType": "CodeChangeMerged",
                          "eventVersion": 1,
                          "occurredAt": "2026-10-04T12:00:00Z",
                          "receivedAt": "2026-10-04T12:00:02Z",
                          "organizationId": "org-1",
                          "source": {"provider": "github", "integrationId": "integration-1"},
                          "actor": {"type": "UNKNOWN", "id": "release-manager"},
                          "correlation": null,
                          "payload": {
                            "deliveryId": "delivery-1",
                            "repositoryId": "12345",
                            "repositoryFullName": "changeguard/example",
                            "pullRequestNumber": 42,
                            "pullRequestTitle": "Fix café checkout 🌱",
                            "commitSha": "cccccccccccccccccccccccccccccccccccccccc",
                            "sourceBranch": "feature/checkout",
                            "targetBranch": "main"
                          }
                        }
                        """.formatted(event.eventId())));
                assertThat(mapper.readValue(value, CodeChangeMergedEvent.class)).isEqualTo(event);
                assertThat(headers).isEmpty();
            }
        });
    }
}
