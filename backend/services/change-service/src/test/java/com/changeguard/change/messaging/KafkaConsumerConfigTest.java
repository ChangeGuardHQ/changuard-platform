package com.changeguard.change.messaging;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaConsumerConfig.class, KafkaErrorHandlerConfig.class);

    @Test
    void brokerSecurityAndListenerOverridesReachTheContainer() {
        contextRunner.withPropertyValues(
                "spring.kafka.bootstrap-servers=kafka.internal:19092",
                "spring.kafka.properties.security.protocol=SASL_SSL",
                "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                "spring.kafka.consumer.group-id=infrastructure-test",
                "spring.kafka.listener.concurrency=2")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConsumerFactory.class);
                    ConsumerFactory<?, ?> consumerFactory = context.getBean(ConsumerFactory.class);
                    var settings = new ConsumerConfig(consumerFactory.getConfigurationProperties());
                    assertThat(settings.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
                            .containsExactly("kafka.internal:19092");
                    assertThat(settings.getString("security.protocol")).isEqualTo("SASL_SSL");
                    assertThat(settings.getString("sasl.mechanism")).isEqualTo("SCRAM-SHA-512");
                    assertThat(settings.getString(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("infrastructure-test");
                    assertThat(settings.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                    var container = factory.createContainer("infrastructure-probe");
                    assertThat(container.getConcurrency()).isEqualTo(2);
                    assertThat(container.isAutoStartup()).isFalse();
                    assertThat(container.getContainerProperties().getAckMode())
                            .isEqualTo(ContainerProperties.AckMode.RECORD);
                    assertThat(container.getCommonErrorHandler()).isSameAs(context.getBean(CommonErrorHandler.class));
                    assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
                });
    }

    @Test
    void headerlessJsonPreservesTheEnvelopeAndIgnoresUntrustedTypeHeaders() {
        contextRunner.run(context -> {
            ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
            var properties = factory.getConfigurationProperties();
            var settings = new ConsumerConfig(properties);
            try (Deserializer<?> deserializer = settings.getConfiguredInstance(
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, Deserializer.class)) {
                deserializer.configure(properties, false);
                var headers = new RecordHeaders();
                headers.add("__TypeId__", "untrusted.RemoteClass".getBytes(StandardCharsets.UTF_8));
                Object result = deserializer.deserialize("probe", headers, """
                        {"eventId":"event-1","payload":{"futureField":"preserved"}}
                        """.getBytes(StandardCharsets.UTF_8));
                assertThat(result).isInstanceOf(JsonNode.class);
                var json = (JsonNode) result;
                assertThat(json.path("eventId").asString()).isEqualTo("event-1");
                assertThat(json.path("payload").path("futureField").asString()).isEqualTo("preserved");
            }
        });
    }

    @Test
    void malformedJsonIsReportedToTheContainerAsADeserializationFailure() {
        contextRunner.run(context -> {
            ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
            var properties = factory.getConfigurationProperties();
            var settings = new ConsumerConfig(properties);
            try (Deserializer<?> deserializer = settings.getConfiguredInstance(
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, Deserializer.class)) {
                deserializer.configure(properties, false);
                var headers = new RecordHeaders();
                assertThat(deserializer.deserialize("probe", headers, "{".getBytes(StandardCharsets.UTF_8))).isNull();
                assertThat(headers).isNotEmpty();
            }
        });
    }
}
