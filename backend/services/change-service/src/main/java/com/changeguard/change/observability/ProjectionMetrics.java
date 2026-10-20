package com.changeguard.change.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed labels avoid tenant/repository cardinality and never expose event payloads or identities. */
@Component
public class ProjectionMetrics implements RetryListener {
    private final MeterRegistry meters;

    public ProjectionMetrics(MeterRegistry meters) { this.meters = meters; }

    public void committed(boolean duplicate) {
        // Count successful projections only after the database transaction actually commits.
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Projection metrics require an active transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                meters.counter("changeguard.change.ingestion", "result", duplicate ? "duplicate" : "applied").increment();
            }
        });
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
        meters.counter("changeguard.change.delivery.failures").increment();
        if (attempt > 1) meters.counter("changeguard.change.delivery.retries").increment();
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
        meters.counter("changeguard.change.delivery.quarantined").increment();
    }

    @Override
    public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        meters.counter("changeguard.change.delivery.recovery.failures").increment();
    }
}
