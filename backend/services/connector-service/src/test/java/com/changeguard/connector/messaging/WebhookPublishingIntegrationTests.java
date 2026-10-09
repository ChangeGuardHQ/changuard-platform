package com.changeguard.connector.messaging;

import java.time.Duration;
import java.util.UUID;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import com.changeguard.events.CodeEventSchemas;
import com.changeguard.events.code.PullRequestMerged;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
        "changeguard.kafka.topics.code-events=changeguard.integration.code-events.v1",
        "spring.kafka.producer.compression-type=lz4",
        "changeguard.outbox.poll-interval=100ms"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@Testcontainers
@DirtiesContext
class WebhookPublishingIntegrationTests {
    private static final String TOPIC = "changeguard.integration.code-events.v1";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ConnectorIntegrationRepository integrations;
    private static final Network NETWORK = Network.newNetwork();
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(System.getProperty("changeguard.test.kafka-image", "changuard-kafka:4.2.2-security.2"))
                    .asCompatibleSubstituteFor("apache/kafka"))
            .withNetwork(NETWORK).withNetworkAliases("kafka").withListener("kafka:19092");
    @Container
    private static final GenericContainer<?> REGISTRY = new GenericContainer<>(
            System.getProperty("changeguard.test.schema-registry-image", "changuard-schema-registry:8.3.2-security.2"))
            .withNetwork(NETWORK).withExposedPorts(8081)
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
            .dependsOn(KAFKA).waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));
    @Autowired private SchemaRegistryClient registry;
    @Autowired private JsonMapper json;
    @Autowired private com.changeguard.connector.persistence.ConnectorIntakeStore intake;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.kafka.producer.properties.schema.registry.url", WebhookPublishingIntegrationTests::registryUrl);
    }

    private static String registryUrl() {
        return "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081);
    }

    @BeforeEach
    void connectRepository() throws Exception {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(java.util.Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            try {
                admin.createTopics(java.util.List.of(new org.apache.kafka.clients.admin.NewTopic(TOPIC, 1, (short) 1)))
                        .all().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException error) {
                if (!(error.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw error;
            }
        }
        jdbc.sql("TRUNCATE connector.outbox_events, connector.webhook_receipts, "
                + "connector.connected_repositories, connector.integrations").update();
        integrations.createGitHubIntegration("org-integration", "integration-e2e", 123);
        integrations.connectRepository("org-integration", "integration-e2e", "12345", "changeguard/example", "main");
    }

    @Test
    void signedWebhookAndRedeliveryProduceOneRegisteredAvroRecordThroughTheScheduledRelay() throws Exception {
        var properties = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "outbox-test-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        properties.put("schema.registry.url", registryUrl());
        properties.put("specific.avro.reader", true);
        var consumers = new DefaultKafkaConsumerFactory<String, PullRequestMerged>(properties);
        try (var consumer = consumers.createConsumer()) {
            var partition = new org.apache.kafka.common.TopicPartition(TOPIC, 0);
            consumer.assign(java.util.List.of(partition));
            consumer.seekToEnd(java.util.List.of(partition));
            consumer.position(partition); // Resolve the end before this test produces records.
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
            var body = record.value();
            assertThat(body.getEventType()).isEqualTo("PullRequestMerged");
            assertThat(body.getOrganizationId()).isEqualTo("org-integration");
            assertThat(body.getSource().getIntegrationId()).isEqualTo("integration-e2e");
            assertThat(body.getPayload().getDeliveryId()).isEqualTo("integration-delivery");
            assertThat(registry.getCompatibility(CodeEventSchemas.subject(TOPIC, body.getSchema())))
                    .isEqualTo("BACKWARD_TRANSITIVE");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.sql("SELECT status FROM connector.outbox_events").query(String.class).single())
                            .isEqualTo("PUBLISHED"));
            assertThat(body).isEqualTo(new PullRequestMergedAvroMapper(json).fromOutbox(
                    json.readTree(jdbc.sql("SELECT payload::text FROM connector.outbox_events")
                            .query(String.class).single())));
            assertThat(jdbc.sql("SELECT count(*) FROM connector.webhook_receipts").query(Integer.class).single())
                    .isEqualTo(1);
            assertThat(consumer.poll(Duration.ofSeconds(1)).isEmpty()).isTrue();
        }
    }

    @Test
    void registryRegistersAllCodeEventsAndRejectsIncompatibleEvolution() throws Exception {
        for (var schema : CodeEventSchemas.all()) {
            String subject = CodeEventSchemas.subject(TOPIC, schema);
            assertThat(registry.getCompatibility(subject)).isEqualTo(CodeEventSchemas.COMPATIBILITY);
            assertThat(registry.getAllVersions(subject)).containsExactly(1);
        }
        String subject = "compatibility-test-" + UUID.randomUUID();
        registry.updateCompatibility(subject, CodeEventSchemas.COMPATIBILITY);
        var original = new AvroSchema("{\"type\":\"record\",\"name\":\"Evolution\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"}]}");
        registry.register(subject, original);
        var compatible = new AvroSchema("{\"type\":\"record\",\"name\":\"Evolution\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null}]}");
        registry.register(subject, compatible);
        var incompatible = new AvroSchema("{\"type\":\"record\",\"name\":\"Evolution\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"required\",\"type\":\"string\"}]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.register(subject, incompatible))
                .isInstanceOf(RestClientException.class)
                .satisfies(error -> assertThat(((RestClientException) error).getStatus()).isEqualTo(409));
        assertThat(registry.getAllVersions(subject)).containsExactly(1, 2);
    }

    @Test
    void queuedLegacyEnvelopePublishesToAvroTopicWithoutRewritingStoredIdentity() throws Exception {
        var properties = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "legacy-test-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        properties.put("schema.registry.url", registryUrl());
        properties.put("specific.avro.reader", true);
        var consumers = new DefaultKafkaConsumerFactory<String, PullRequestMerged>(properties);
        try (var consumer = consumers.createConsumer()) {
            var partition = new org.apache.kafka.common.TopicPartition(TOPIC, 0);
            consumer.assign(java.util.List.of(partition));
            consumer.seekToEnd(java.util.List.of(partition));
            consumer.position(partition); // Resolve the end before this test produces records.
            var merge = new com.changeguard.connector.normalization.GitHubEventNormalizer(json)
                    .normalize("pull_request", "legacy-delivery", fixture()).orElseThrow();
            var event = com.changeguard.connector.event.PullRequestMergedEvent.fromGitHub(merge,
                    "org-integration", "integration-e2e", java.time.Instant.parse("2026-10-07T17:00:00Z"));
            // Seed an original pre-Avro row atomically, before the scheduled relay can see it.
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                intake.accept(event);
                jdbc.sql("""
                        UPDATE connector.outbox_events
                        SET event_type = 'CodeChangeMerged', topic = 'changeguard.code-events',
                            payload = jsonb_set(payload, '{eventType}', '\"CodeChangeMerged\"'::jsonb)
                        WHERE event_id = :event
                        """).param("event", event.eventId()).update();
            });
            var record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(20));
            assertThat(record.value().getEventType()).isEqualTo("PullRequestMerged");
            assertThat(record.value().getEventId()).isEqualTo(event.eventId());
            assertThat(record.value().getReceivedAt()).isEqualTo(event.receivedAt());
            assertThat(record.key()).isEqualTo("12345");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.sql("SELECT status FROM connector.outbox_events").query(String.class).single())
                            .isEqualTo("PUBLISHED"));
            assertThat(jdbc.sql("SELECT event_type FROM connector.outbox_events").query(String.class).single())
                    .isEqualTo("CodeChangeMerged");
            assertThat(jdbc.sql("SELECT topic FROM connector.outbox_events").query(String.class).single())
                    .isEqualTo("changeguard.code-events");
            assertThat(json.readTree(jdbc.sql("SELECT payload::text FROM connector.outbox_events")
                    .query(String.class).single()).path("eventType").asString()).isEqualTo("CodeChangeMerged");
        }
    }
}
