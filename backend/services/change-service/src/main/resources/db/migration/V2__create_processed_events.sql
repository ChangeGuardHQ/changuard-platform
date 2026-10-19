-- Receipts and their projections must be committed or rolled back together.
CREATE TABLE processed_events (
    organization_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    change_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, event_id),
    FOREIGN KEY (organization_id, change_id) REFERENCES change_records (organization_id, id)
);
CREATE INDEX processed_events_change ON processed_events (organization_id, change_id);
