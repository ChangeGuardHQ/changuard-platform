package com.changeguard.change.idempotency;

import java.time.Instant;
import java.util.UUID;

/** Durable receipt committed in the same transaction as its change projection. */
public record ProcessedEvent(String organizationId, String eventId, String fingerprint,
        UUID changeId, Instant processedAt) {
}
