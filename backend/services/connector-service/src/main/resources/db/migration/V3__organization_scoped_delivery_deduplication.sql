-- V1 may already be applied: preserve its checksum and evolve the schema here.
-- The new intake statement explicitly names organization ownership in its
-- ON CONFLICT key. Keep the earlier key so older instances can still accept
-- deliveries during a rolling upgrade. Integration IDs are globally unique,
-- so both constraints enforce the same durable delivery identity.
ALTER TABLE connector.webhook_receipts
    ADD CONSTRAINT webhook_receipts_organization_integration_delivery_key
        UNIQUE (organization_id, integration_id, delivery_id);
