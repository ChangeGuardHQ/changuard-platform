package com.changeguard.connector.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.config.OutboxProperties;
import com.changeguard.connector.event.CodeChangeMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntakeStore;
import com.changeguard.connector.persistence.OutboxEventRepository;
import com.changeguard.connector.persistence.OutboxEventRepository.ClaimedEvent;
import com.changeguard.connector.persistence.OutboxEventRepository.OutboxEvent;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "changeguard.outbox.enabled=false")
@Import(PostgresTestConfiguration.class)
class OutboxPublisherTests {
    @Autowired private JdbcClient jdbc;
    @Autowired private ConnectorIntegrationRepository integrations;
    @Autowired private ConnectorIntakeStore intake;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private JsonMapper json;
    @MockitoBean private CodeEventProducer producer;

    @BeforeEach
    void persistEvent() {
        jdbc.sql("TRUNCATE connector.outbox_events, connector.webhook_receipts, "
                + "connector.connected_repositories, connector.integrations").update();
        integrations.createGitHubIntegration("org-a", "integration-a", 123);
        integrations.connectRepository("org-a", "integration-a", "12345", "acme/service", "main");
        var merge = new GitHubMergedPullRequest("delivery-1", "12345", "acme/service", 42, "Ship change",
                "cccccccccccccccccccccccccccccccccccccccc", "feature", "main", "merger", Instant.now());
        intake.accept(CodeChangeMergedEvent.fromGitHub(merge, "org-a", "integration-a", Instant.now()));
    }

    @Test
    void waitsForBrokerAcknowledgementAndUsesThePersistedEnvelopeAndDestination() throws Exception {
        jdbc.sql("UPDATE connector.outbox_events SET topic = 'original.topic', partition_key = 'original.key'").update();
        var original = outbox.findPending(10).getFirst();
        var ack = new CompletableFuture<SendResult<String, Object>>();
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class))).thenReturn(ack);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var work = executor.submit(() -> worker(Duration.ofSeconds(5), outbox).publishAvailable());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(producer).publish(eq(original), eq(json.readTree(original.payload()))));
            assertThat(status()).isEqualTo("PENDING");
            assertThat(jdbc.sql("SELECT published_at IS NULL AND lease_token IS NOT NULL FROM connector.outbox_events")
                    .query(Boolean.class).single()).isTrue();
            ack.complete(mock(SendResult.class));
            work.get(5, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL AND lease_token IS NULL FROM connector.outbox_events")
                .query(Boolean.class).single()).isTrue();
        worker(Duration.ofSeconds(5), outbox).publishAvailable();
        verify(producer, times(1)).publish(any(OutboxEvent.class), any(JsonNode.class));
    }

    @Test
    void asynchronousSendFailurePersistsBackoffAndKeepsTheEventPending() {
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));
        worker(Duration.ofSeconds(1), outbox).publishAvailable();
        assertRetry("SEND_FAILED");
    }

    @Test
    void synchronousSendFailurePersistsBackoff() {
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class))).thenThrow(new KafkaException("send failed"));
        worker(Duration.ofSeconds(1), outbox).publishAvailable();
        assertRetry("SEND_FAILED");
    }

    @Test
    void acknowledgementTimeoutDoesNotMarkPublishedWhenAnOldFutureCompletes() {
        var ack = new CompletableFuture<SendResult<String, Object>>();
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class))).thenReturn(ack);
        worker(Duration.ofMillis(20), outbox).publishAvailable();
        assertRetry("ACK_TIMEOUT");
        ack.complete(mock(SendResult.class));
        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    void databaseFailureAfterBrokerAcknowledgementLeavesTheLeaseRecoverable() {
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var failingRepository = spy(new OutboxEventRepository(jdbc));
        doThrow(new TransientDataAccessResourceException("database unavailable"))
                .when(failingRepository).markPublished(any(ClaimedEvent.class));
        assertThatThrownBy(() -> worker(Duration.ofSeconds(1), failingRepository).publishAvailable())
                .isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(outbox.claimNext(Duration.ofSeconds(10))).isEmpty();
        expireLease();
        worker(Duration.ofSeconds(1), outbox).publishAvailable();
        assertThat(status()).isEqualTo("PUBLISHED");
        verify(producer, times(2)).publish(any(OutboxEvent.class), any(JsonNode.class));
    }

    @Test
    void concurrentWorkersCannotClaimTheSameActiveLease() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> outbox.claimNext(Duration.ofSeconds(10)))).toList();
            int claimed = 0;
            for (var call : calls) {
                if (call.get(5, TimeUnit.SECONDS).isPresent()) {
                    claimed++;
                }
            }
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseRecoveryFencesThePreviousWorker() {
        var original = outbox.claimNext(Duration.ofSeconds(10)).orElseThrow();
        assertThat(outbox.markPublished(new ClaimedEvent(original.event(), UUID.randomUUID(), 1))).isFalse();
        expireLease();
        var recovered = new OutboxEventRepository(jdbc).claimNext(Duration.ofSeconds(10)).orElseThrow();
        assertThat(recovered.event()).isEqualTo(original.event());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(original.leaseToken());
        assertThat(outbox.markPublished(original)).isFalse();
        assertThat(outbox.releaseForRetry(original, Duration.ofSeconds(1), "SEND_FAILED")).isFalse();
        assertThat(outbox.markPublished(recovered)).isTrue();
    }

    @Test
    void retryStateSurvivesRepositoryRecreationAndPublishesTheOriginalEvent() {
        var original = outbox.findPending(10).getFirst();
        when(producer.publish(any(OutboxEvent.class), any(JsonNode.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        worker(Duration.ofSeconds(1), outbox).publishAvailable();
        var restarted = new OutboxEventRepository(jdbc);
        assertThat(restarted.claimNext(Duration.ofSeconds(10))).isEmpty();
        jdbc.sql("UPDATE connector.outbox_events SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'").update();
        worker(Duration.ofSeconds(1), restarted).publishAvailable();
        assertThat(status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT attempt_count FROM connector.outbox_events").query(Integer.class).single()).isEqualTo(2);
        verify(producer, times(2)).publish(eq(original), eq(json.readTree(original.payload())));
    }

    private OutboxPublisher worker(Duration timeout, OutboxEventRepository repository) {
        return new OutboxPublisher(repository, producer, json, new OutboxProperties(1, timeout,
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    private String status() {
        return jdbc.sql("SELECT status FROM connector.outbox_events").query(String.class).single();
    }

    private void expireLease() {
        jdbc.sql("UPDATE connector.outbox_events SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'").update();
    }

    private void assertRetry(String failure) {
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.sql("""
                SELECT lease_token IS NULL AND published_at IS NULL AND attempt_count = 1
                       AND next_attempt_at > clock_timestamp() AND last_failure_code = :failure
                FROM connector.outbox_events
                """).param("failure", failure).query(Boolean.class).single()).isTrue();
        assertThat(new OutboxEventRepository(jdbc).claimNext(Duration.ofSeconds(10))).isEmpty();
    }
}
