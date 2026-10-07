package com.changeguard.connector.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Drives the relay independently of HTTP request processing. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "changeguard.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishingSchedule {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublishingSchedule.class);
    private final OutboxPublisher publisher;

    public OutboxPublishingSchedule(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

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
