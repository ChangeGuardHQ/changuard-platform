package com.changeguard.connector.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.changeguard.connector.config.OutboxProperties;
import com.changeguard.connector.persistence.OutboxEventRepository;
import com.changeguard.connector.persistence.OutboxEventRepository.ClaimedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes leased outbox rows outside database transactions. Acknowledgement
 * precedes the publication update; crashes in between may redeliver the same
 * event ID, so downstream consumers must deduplicate.
 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxEventRepository outbox;
    private final CodeEventProducer producer;
    private final JsonMapper json;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxEventRepository outbox, CodeEventProducer producer,
            JsonMapper json, OutboxProperties properties) {
        this.outbox = outbox;
        this.producer = producer;
        this.json = json;
        this.properties = properties;
    }

    /** Processes a bounded number of due events; each send gets its own fresh lease. */
    public void publishAvailable() {
        for (int index = 0; index < properties.batchSize() && !Thread.currentThread().isInterrupted(); index++) {
            var claim = outbox.claimNext(properties.leaseDuration());
            if (claim.isEmpty()) {
                return;
            }
            publish(claim.get());
        }
    }

    private void publish(ClaimedEvent claim) {
        String failure = null;
        try {
            producer.publish(claim.event(), json.readTree(claim.event().payload()))
                    .get(properties.acknowledgementTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = "INTERRUPTED";
        } catch (TimeoutException exception) {
            failure = "ACK_TIMEOUT";
        } catch (JacksonException exception) {
            failure = "INVALID_PAYLOAD";
        } catch (ExecutionException | RuntimeException exception) {
            failure = "SEND_FAILED";
        }
        // Database failures deliberately propagate: the row remains leased and
        // becomes eligible again at lease expiry, including after a broker ack.
        // Keep the update outside the send catch so it is never mistaken for a failed send.
        if (failure == null) {
            if (!outbox.markPublished(claim)) {
                log.warn("Outbox acknowledgement ignored after lease ownership changed");
            }
        } else {
            outbox.releaseForRetry(claim, properties.retryDelay(claim.attemptCount()), failure);
            log.warn("Outbox publication will retry ({}, attempt {})", failure, claim.attemptCount());
        }
    }
}
