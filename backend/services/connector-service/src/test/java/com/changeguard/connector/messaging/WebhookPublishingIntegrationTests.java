package com.changeguard.connector.messaging;

import java.time.Duration;
import java.util.UUID;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static com.changeguard.connector.github.GitHubWebhookTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"changeguard.github.webhook-secret=" + SECRET,
        "changeguard.kafka.topics.code-events=changeguard.integration.code-events",
        "changeguard.outbox.poll-interval=100ms"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = "changeguard.integration.code-events",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class WebhookPublishingIntegrationTests {
    private static final String TOPIC = "changeguard.integration.code-events";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ConnectorIntegrationRepository integrations;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private JsonMapper json;

    @Test
    void signedWebhookAndRedeliveryProduceOneJsonRecordThroughTheScheduledRelay() throws Exception {
        integrations.createGitHubIntegration("org-integration", "integration-e2e", 123);
        integrations.connectRepository("org-integration", "integration-e2e", "12345", "changeguard/example", "main");
        var properties = KafkaTestUtils.consumerProps(broker, "outbox-test-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var consumers = new DefaultKafkaConsumerFactory<String, String>(properties);
        try (var consumer = consumers.createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            byte[] payload = fixture();
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request").header("X-GitHub-Delivery", "integration-delivery")
                        .header("X-Hub-Signature-256", signature(payload)).content(payload))
                        .andExpect(status().isAccepted());
            }
            var record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(20));
            assertThat(record.key()).isEqualTo("12345");
            assertThat(record.headers().toArray()).isEmpty();
            var body = json.readTree(record.value());
            assertThat(body.isObject()).isTrue(); // Detect accidentally publishing a quoted JSON string.
            assertThat(body.path("organizationId").asString()).isEqualTo("org-integration");
            assertThat(body.path("source").path("integrationId").asString()).isEqualTo("integration-e2e");
            assertThat(body.path("payload").path("deliveryId").asString()).isEqualTo("integration-delivery");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.sql("SELECT status FROM connector.outbox_events").query(String.class).single())
                            .isEqualTo("PUBLISHED"));
            assertThat(body).isEqualTo(json.readTree(jdbc.sql("SELECT payload::text FROM connector.outbox_events")
                    .query(String.class).single()));
            assertThat(jdbc.sql("SELECT count(*) FROM connector.webhook_receipts").query(Integer.class).single())
                    .isEqualTo(1);
            assertThat(consumer.poll(Duration.ofSeconds(1)).isEmpty()).isTrue();
        }
    }
}
