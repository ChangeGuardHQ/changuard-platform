package com.changeguard.change.observability;

import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Observations must distinguish durable success, failed recovery and intentional maintenance. */
class ProjectionObservabilityTest {
    // This Micrometer version has close(), but does not yet implement AutoCloseable.
    private static class TestRegistry extends SimpleMeterRegistry implements AutoCloseable { }
    @Test
    void ingestionCountersWaitForCommitAndIgnoreRollback() {
        try (var registry = new TestRegistry()) {
            var metrics = new ProjectionMetrics(registry);
            TransactionSynchronizationManager.initSynchronization();
            try {
                metrics.committed(false);
                assertThat(registry.find("changeguard.change.ingestion").counter()).isNull();
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
                assertThat(registry.get("changeguard.change.ingestion").tag("result", "applied").counter().count()).isEqualTo(1);
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            TransactionSynchronizationManager.initSynchronization();
            try {
                metrics.committed(true);
                TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(registry.find("changeguard.change.ingestion").tag("result", "duplicate").counter()).isNull();
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            var record = new ConsumerRecord<>("source", 0, 0, "private-repo", "private-payload");
            metrics.failedDelivery(record, new IllegalStateException(), 1);
            metrics.failedDelivery(record, new IllegalStateException(), 2);
            metrics.recoveryFailed(record, new IllegalStateException(), new IllegalStateException());
            assertThat(registry.get("changeguard.change.delivery.recovery.failures").counter().count()).isEqualTo(1);
            assertThat(registry.find("changeguard.change.delivery.quarantined").counter()).isNull();
            metrics.recovered(record, new IllegalStateException());
            assertThat(registry.get("changeguard.change.delivery.quarantined").counter().count()).isEqualTo(1);
            assertThat(registry.get("changeguard.change.delivery.retries").counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("result")));
        }
    }

    @Test
    void enabledConsumerReportsStoppedPausedUnassignedAndRunningStates() {
        var listeners = mock(KafkaListenerEndpointRegistry.class);
        var container = mock(MessageListenerContainer.class);
        when(listeners.getListenerContainer("code-change-events")).thenReturn(container);
        try (var registry = new TestRegistry()) {
            var health = new ProjectionHealthIndicator(listeners, new KafkaProperties(), registry);
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            assertThat(registry.get("changeguard.change.consumer.running").gauge().value()).isZero();
            when(container.isRunning()).thenReturn(true);
            assertThat(health.health().getStatus().getCode()).isEqualTo("UNKNOWN");
            when(container.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("source", 0)));
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(registry.get("changeguard.change.consumer.running").gauge().value()).isEqualTo(1);
            when(container.isPauseRequested()).thenReturn(true);
            assertThat(health.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        }
    }

    @Test
    void intentionallyDisabledConsumerDoesNotFailTheApplicationHealth() {
        var kafka = new KafkaProperties();
        kafka.getListener().setAutoStartup(false);
        try (var registry = new TestRegistry()) {
            var health = new ProjectionHealthIndicator(mock(KafkaListenerEndpointRegistry.class), kafka, registry);
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(health.health().getDetails()).containsEntry("enabled", false);
        }
    }
}
