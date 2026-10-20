package com.changeguard.change.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Projection availability is independent of serving previously stored records through the read API. */
@Component("codeEventConsumerHealthIndicator")
public class ProjectionHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry listeners;
    private final boolean enabled;

    public ProjectionHealthIndicator(KafkaListenerEndpointRegistry listeners, KafkaProperties kafka, MeterRegistry meters) {
        this.listeners = listeners;
        this.enabled = kafka.getListener().isAutoStartup();
        Gauge.builder("changeguard.change.consumer.enabled", () -> enabled ? 1 : 0).register(meters);
        Gauge.builder("changeguard.change.consumer.running", this, indicator -> indicator.running() ? 1 : 0).register(meters);
    }

    private boolean running() {
        var listener = listeners.getListenerContainer("code-change-events");
        return listener != null && listener.isRunning();
    }

    @Override
    public Health health() {
        if (!enabled) return Health.up().withDetail("enabled", false).build();
        var listener = listeners.getListenerContainer("code-change-events");
        if (listener == null || !listener.isRunning())
            return Health.down().withDetail("reason", "consumer_stopped").build();
        if (listener.isPauseRequested())
            return Health.outOfService().withDetail("reason", "consumer_paused").build();
        var assignments = listener.getAssignedPartitions();
        if (assignments == null || assignments.isEmpty())
            return Health.unknown().withDetail("reason", "awaiting_partition_assignment").build();
        // Assignment is a container-state check, not proof of broker connectivity or zero backlog.
        return Health.up().withDetail("enabled", true).withDetail("assignedPartitions", assignments.size()).build();
    }
}
