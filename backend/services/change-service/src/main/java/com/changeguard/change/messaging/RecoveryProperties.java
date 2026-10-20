package com.changeguard.change.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded consumer-thread backoff stays comfortably below Kafka's default poll interval. */
@ConfigurationProperties("changeguard.kafka.recovery")
public record RecoveryProperties(String deadLetterTopic, Duration retryInterval, int retries) {
    public RecoveryProperties {
        org.springframework.util.Assert.hasText(deadLetterTopic, "A dedicated dead-letter topic is required");
        org.springframework.util.Assert.isTrue(retryInterval != null && !retryInterval.isNegative()
                && retryInterval.compareTo(Duration.ofSeconds(10)) <= 0, "Retry interval must be 0..10 seconds");
        org.springframework.util.Assert.isTrue(retries >= 0 && retries <= 10, "Retries must be 0..10");
    }
}
