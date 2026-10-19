package com.changeguard.change.idempotency;

import java.util.Optional;
import java.util.UUID;
import com.changeguard.change.change.MergedChange;
import com.changeguard.change.exception.EventProcessingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Receipts are never committed separately from the projection they protect. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {
    private final ProcessedEventRepository receipts;

    public IdempotencyService(ProcessedEventRepository receipts) { this.receipts = receipts; }

    public Optional<ProcessedEvent> previouslyProcessed(MergedChange merge) {
        var existing = receipts.find(merge.organizationId(), merge.eventId());
        existing.ifPresent(receipt -> verify(receipt, merge));
        return existing;
    }

    public void record(MergedChange merge, UUID changeId) {
        receipts.insertIfAbsent(merge.organizationId(), merge.eventId(), merge.fingerprint(), changeId);
        // A competing transaction may have inserted the same event while this one was projecting.
        var stored = receipts.find(merge.organizationId(), merge.eventId()).orElseThrow();
        verify(stored, merge);
        if (!stored.changeId().equals(changeId)) {
            throw new EventProcessingException("Event ID already belongs to another change");
        }
    }

    private void verify(ProcessedEvent receipt, MergedChange merge) {
        if (!receipt.fingerprint().equals(merge.fingerprint())) {
            throw new EventProcessingException("Event ID was reused with different merge facts");
        }
    }
}
