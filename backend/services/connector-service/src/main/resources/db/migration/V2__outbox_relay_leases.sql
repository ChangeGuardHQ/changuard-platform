-- Keep V1 immutable. Durable leases recover interrupted workers without holding
-- a database transaction open during Kafka I/O. Attempts and backoff survive restarts.
ALTER TABLE connector.outbox_events
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_failure_code TEXT,
    ADD CONSTRAINT outbox_lease_pair CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    ADD CONSTRAINT outbox_published_not_leased CHECK (status <> 'PUBLISHED' OR lease_token IS NULL);

CREATE INDEX outbox_events_due_idx
    ON connector.outbox_events (next_attempt_at, created_at, event_id) WHERE status = 'PENDING';
