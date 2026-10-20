package com.changeguard.change.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.changeguard.change.AvroFixtures;
import com.changeguard.change.PostgresTestConfiguration;
import com.changeguard.change.change.ChangeRecordRepository;
import com.changeguard.events.CodeEventSchemas;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Registry framing, broker offsets, database commits, replays and authenticated reads in one flow. */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.group-id=change-projection-wire", "changeguard.kafka.topics.code-events=changeguard.projection-test.v1",
        "changeguard.kafka.recovery.dead-letter-topic=changeguard.projection-test.dlt",
        "changeguard.api.organizations[0].organization-id=org-wire",
        "changeguard.api.organizations[0].readers[0]=infrastructure-test"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, ChangeProjectionIntegrationTest.TopicConfiguration.class})
@Testcontainers
@DirtiesContext
class ChangeProjectionIntegrationTest {
    private static final String TOPIC = "changeguard.projection-test.v1";
    private static final String GROUP = "change-projection-wire";
    private static final Network NETWORK = Network.newNetwork();
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("changeguard.test.kafka-image", "changuard-kafka:4.2.2-security.2"))
            .asCompatibleSubstituteFor("apache/kafka"))
            .withNetwork(NETWORK).withNetworkAliases("kafka").withListener("kafka:19092");
    @Container
    static final GenericContainer<?> REGISTRY = new GenericContainer<>(
            System.getProperty("changeguard.test.schema-registry-image", "changuard-schema-registry:8.3.2-security.2"))
            .withNetwork(NETWORK).withExposedPorts(8081)
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
            .dependsOn(KAFKA).waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.kafka.consumer.properties.schema.registry.url", ChangeProjectionIntegrationTest::registryUrl);
    }
    static String registryUrl() { return "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081); }

    @Autowired ChangeRecordRepository changes;
    @Autowired JdbcClient jdbc;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired MockMvc mvc;

    @Test
    void mergeIsQueryableAfterCommitAndReplayIsSafeWhileInvalidRecordsAreQuarantined() throws Exception {
        try (var registry = new CachedSchemaRegistryClient(registryUrl(), 20)) {
            for (var schema : CodeEventSchemas.all()) {
                String subject = CodeEventSchemas.subject(TOPIC, schema);
                registry.updateCompatibility(subject, CodeEventSchemas.COMPATIBILITY);
                registry.register(subject, new AvroSchema(schema));
            }
        }
        var container = listeners.getListenerContainer("code-change-events");
        ContainerTestUtils.waitForAssignment(container, 1);
        var partition = new TopicPartition(TOPIC, 0);
        Map<String, Object> properties = Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "schema.registry.url", registryUrl(), "auto.register.schemas", false,
                "key.serializer", StringSerializer.class, "value.serializer", KafkaAvroSerializer.class,
                "value.subject.name.strategy", "io.confluent.kafka.serializers.subject.TopicRecordNameStrategy");
        try (var producer = new KafkaProducer<String, Object>(properties);
                var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var event = AvroFixtures.merged("wire-event", "org-wire");
            for (int delivery = 0; delivery < 2; delivery++) {
                producer.send(new ProducerRecord<>(TOPIC, "repo-wire", event)).get(10, TimeUnit.SECONDS);
            }
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(changes.count()).isEqualTo(1);
                assertThat(receiptCount()).isEqualTo(1);
                assertThat(committedOffset(admin, partition)).isEqualTo(2);
            });
            var change = changes.findByOrganizationIdAndProviderAndRepositoryIdAndPullRequestNumber(
                    "org-wire", "github", "repo-wire", 7).orElseThrow();
            mvc.perform(get("/api/v1/changes/" + change.getId() + "/timeline").param("organizationId", "org-wire")
                            .with(httpBasic("infrastructure-test", "test-password")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(1))
                    .andExpect(jsonPath("$.entries[0].eventId").value("wire-event"));
            mvc.perform(get("/api/v1/changes").param("organizationId", "other-org")
                            .with(httpBasic("infrastructure-test", "test-password"))).andExpect(status().isForbidden());

            // Rewind a stopped group to simulate replay after restart, not merely another producer send.
            container.stop();
            admin.alterConsumerGroupOffsets(GROUP, Map.of(partition, new OffsetAndMetadata(0))).all().get(10, TimeUnit.SECONDS);
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(committedOffset(admin, partition)).isEqualTo(2);
                assertThat(changes.count()).isEqualTo(1);
                assertThat(receiptCount()).isEqualTo(1);
            });

            // A structurally valid Avro message may still violate the semantic version contract.
            var invalid = AvroFixtures.merged("unsupported-version", "org-wire");
            invalid.setEventVersion(2);
            producer.send(new ProducerRecord<>(TOPIC, "repo-wire", invalid)).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(committedOffset(admin, partition)).isEqualTo(3));
            assertThat(container.isRunning()).isTrue();
            assertThat(changes.count()).isEqualTo(1);
            assertThat(receiptCount()).isEqualTo(1);
        }
    }

    private long receiptCount() { return jdbc.sql("SELECT count(*) FROM change.processed_events").query(Long.class).single(); }
    private long committedOffset(Admin admin, TopicPartition partition) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        assertThat(offsets).containsKey(partition);
        return offsets.get(partition).offset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TopicConfiguration {
        // Quarantine is explicitly provisioned, including in environments with topic auto-creation disabled.
        @Bean
        org.apache.kafka.clients.admin.NewTopic deadLetterTopic() {
            return TopicBuilder.name("changeguard.projection-test.dlt").partitions(1).replicas(1).build();
        }
        @Bean
        org.apache.kafka.clients.admin.NewTopic codeEventsTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
