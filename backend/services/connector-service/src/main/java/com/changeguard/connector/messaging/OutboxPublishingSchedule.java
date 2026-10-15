package com.changeguard.connector.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically drains the outbox without depending on any incoming HTTP
 * request.
 * <p>
 * This scheduler is enabled when the outbox feature is on and keeps retrying
 * any pending publications in the background using the configured polling
 * interval.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "changeguard.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishingSchedule {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishingSchedule.class);
    private final OutboxPublisher publisher;

    /**
     * Creates the scheduler with the publisher that will perform the relay
     * work.
     *
     * @param publisher the component responsible for publishing queued outbox
     * entries
     */
    public OutboxPublishingSchedule(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Polls for available outbox records and publishes any work that is ready.
     * <p>
     * Database access errors are swallowed and logged so that a transient
     * outage does not stop future polling cycles or leak low-level details from
     * the failing operation.
     */
    @Scheduled(fixedDelayString = "${changeguard.outbox.poll-interval}")
    public void publishAvailable() {
        try {
            publisher.publishAvailable();
        } catch (RuntimeException exception) {
            // An unavailable database must not stop future polls or expose SQL/payload contents.
            log.warn("Outbox poll failed; pending work will recover ({})", exception.getClass().getSimpleName());
        }
    }
}
