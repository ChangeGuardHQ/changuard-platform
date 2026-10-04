package com.changeguard.connector.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.changeguard.connector.config.KafkaProducerConfig;
import com.changeguard.connector.event.CodeChangeMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import com.changeguard.connector.normalization.GitHubEventNormalizer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeEventProducerTests {

    private static final String TOPIC = "changeguard.test.code-events";

    private MockProducer<String, Object> mockProducer;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private CodeEventProducer producer;

    @BeforeEach
    void setUp() {
        mockProducer = new MockProducer<>(false, null,
                new StringSerializer(), new JacksonJsonSerializer<>().noTypeInfo()) {
            // KafkaTemplate releases a producer after each completed send; reuse it for these tests.
            @Override
            public void close() {
            }

            @Override
            public void close(Duration timeout) {
            }
        };
        kafkaTemplate = new KafkaProducerConfig().kafkaTemplate(new MockProducerFactory<>(() -> mockProducer));
        producer = new CodeEventProducer(kafkaTemplate, TOPIC);
    }

    @AfterEach
    void tearDown() {
        kafkaTemplate.destroy();
    }

    @Test
    void publishesToConfiguredTopicWithRepositoryKeyAndWaitsForAcknowledgement() throws Exception {
        CodeChangeMergedEvent event = event("repository-42", "delivery-1");
        var acknowledgement = producer.publish(event);

        assertThat(acknowledgement).isNotDone();
        assertThat(mockProducer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(event.payload().repositoryId());
            assertThat(record.value()).isSameAs(event);
        });
        assertThat(mockProducer.flushed()).isFalse();

        assertThat(mockProducer.completeNext()).isTrue();
        var result = acknowledgement.get(1, TimeUnit.SECONDS);
        assertThat(result.getRecordMetadata().topic()).isEqualTo(TOPIC);
        assertThat(result.getRecordMetadata().offset()).isZero();
        assertThat(result.getProducerRecord().value()).isSameAs(event);
    }

    @Test
    void usesTheSameKeyForMultipleDeliveriesFromARepository() throws Exception {
        CodeChangeMergedEvent first = event("repository-42", "delivery-1");
        CodeChangeMergedEvent second = event("repository-42", "delivery-2");
        var firstAcknowledgement = producer.publish(first);
        var secondAcknowledgement = producer.publish(second);

        assertThat(mockProducer.history()).extracting(record -> record.key())
                .containsExactly("repository-42", "repository-42");
        assertThat(mockProducer.history()).extracting(record -> record.value())
                .containsExactly(first, second);

        assertThat(mockProducer.completeNext()).isTrue();
        assertThat(mockProducer.completeNext()).isTrue();
        assertThat(firstAcknowledgement.get(1, TimeUnit.SECONDS).getRecordMetadata().offset()).isZero();
        assertThat(secondAcknowledgement.get(1, TimeUnit.SECONDS).getRecordMetadata().offset()).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void propagatesAsynchronousFailureWithoutLoggingEventContents(CapturedOutput output) {
        CodeChangeMergedEvent event = event("repository-42", "delivery-1");
        var acknowledgement = producer.publish(event);
        TimeoutException failure = new TimeoutException("Acknowledgement timed out");

        assertThat(mockProducer.errorNext(failure)).isTrue();
        assertThat(acknowledgement).isCompletedExceptionally();
        assertThatThrownBy(acknowledgement::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(KafkaProducerException.class)
                .satisfies(exception -> assertThat(exception.getCause().getCause()).isSameAs(failure));
        assertThat(output).contains(TOPIC)
                .doesNotContain(event.payload().pullRequestTitle(), event.actor().id(), event.payload().commitSha());
    }

    @Test
    void propagatesImmediateSendFailure() {
        SerializationException failure = new SerializationException("Unable to serialize event");
        mockProducer.sendException = failure;

        assertThatThrownBy(() -> producer.publish(event("repository-42", "delivery-1")))
                .isSameAs(failure);
        assertThat(mockProducer.history()).isEmpty();
    }

    @Test
    void rejectsNullEventsBeforeSending() {
        assertThatThrownBy(() -> producer.publish(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Code event is required");
        assertThat(mockProducer.history()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingRepositoryKeysBeforeSending(String repositoryId) {
        assertThatThrownBy(() -> producer.publish(event(repositoryId, "delivery-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Repository ID is required");
        assertThat(mockProducer.history()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingTopicConfiguration(String topic) {
        assertThatThrownBy(() -> new CodeEventProducer(kafkaTemplate, topic))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Code events topic must not be blank");
    }

    @Test
    void registersPublisherAndHonorsTopicEnvironmentOverride() throws Exception {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CodeEventProducer.class)
                .withBean(KafkaTemplate.class, () -> kafkaTemplate)
                .withPropertyValues("CODE_EVENTS_TOPIC=changeguard.override.code-events")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CodeEventProducer.class);
                    var acknowledgement = context.getBean(CodeEventProducer.class)
                            .publish(event("repository-42", "delivery-1"));
                    assertThat(mockProducer.completeNext()).isTrue();
                    assertThat(acknowledgement.get(1, TimeUnit.SECONDS).getRecordMetadata().topic())
                            .isEqualTo("changeguard.override.code-events");
                });
    }

    @Test
    void publishesANormalizedWebhookAsACanonicalEvent() throws Exception {
        GitHubMergedPullRequest merge;
        try (var input = getClass().getResourceAsStream("/github/pull-request-merged.json")) {
            assertThat(input).isNotNull();
            merge = new GitHubEventNormalizer(JsonMapper.builder().build())
                    .normalize("pull_request", "delivery-1", input.readAllBytes()).orElseThrow();
        }
        CodeChangeMergedEvent event = CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                Instant.parse("2026-10-04T12:00:02Z"));

        var acknowledgement = producer.publish(event);
        assertThat(mockProducer.completeNext()).isTrue();
        var record = acknowledgement.get(1, TimeUnit.SECONDS).getProducerRecord();
        assertThat(record.key()).isEqualTo("12345");
        assertThat(record.value()).isEqualTo(event);
        assertThat(event.occurredAt()).isEqualTo(merge.mergedAt());
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-10-04T12:00:02Z"));
        assertThat(event.payload().commitSha()).isEqualTo(merge.commitSha());
    }

    private static CodeChangeMergedEvent event(String repositoryId, String deliveryId) {
        var merge = new GitHubMergedPullRequest(deliveryId, repositoryId, "changeguard/example", 42,
                "Private pull request title", "private-commit-sha", "feature", "main", "private-actor",
                Instant.parse("2026-10-04T12:00:00Z"));
        return CodeChangeMergedEvent.fromGitHub(merge, "org-1", "integration-1",
                Instant.parse("2026-10-04T12:00:02Z"));
    }
}
