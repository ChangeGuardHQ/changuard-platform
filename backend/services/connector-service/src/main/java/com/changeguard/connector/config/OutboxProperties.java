package com.changeguard.connector.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/** Bounds worker throughput, acknowledgement waiting, leases and durable retry delays. */
@ConfigurationProperties("changeguard.outbox")
public record OutboxProperties(int batchSize, Duration acknowledgementTimeout, Duration leaseDuration,
        Duration retryInitialDelay, Duration retryMaxDelay) {
    public OutboxProperties {
        Assert.isTrue(batchSize > 0 && batchSize <= 1000, "Outbox batch size must be between 1 and 1000");
        Assert.isTrue(acknowledgementTimeout != null && acknowledgementTimeout.toMillis() > 0,
                "Outbox acknowledgement timeout must be positive");
        Assert.isTrue(leaseDuration != null && leaseDuration.compareTo(acknowledgementTimeout) > 0,
                "Outbox lease must exceed acknowledgement timeout");
        Assert.isTrue(retryInitialDelay != null && retryInitialDelay.toMillis() > 0,
                "Outbox retry delay must be positive");
        Assert.isTrue(retryMaxDelay != null && retryMaxDelay.compareTo(retryInitialDelay) >= 0,
                "Outbox maximum retry delay must be at least the initial delay");
    }

    public Duration retryDelay(int attemptCount) {
        long millis = retryInitialDelay.toMillis();
        long maximum = retryMaxDelay.toMillis();
        for (int attempt = 1; attempt < attemptCount && millis < maximum; attempt++) {
            millis = millis > maximum / 2 ? maximum : Math.min(maximum, millis * 2);
        }
        return Duration.ofMillis(millis);
    }
}
