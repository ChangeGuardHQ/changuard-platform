-- Connector persistence schema.
-- This schema is owned by the connector service. Other services must use APIs or
-- events rather than querying these tables directly.
--
-- connector.integrations: GitHub App installations owned by an organization.
-- connector.connected_repositories: repositories connected to a specific installation.
-- connector.webhook_receipts: provider deliveries accepted by the connector.
-- connector.outbox_events: durable events emitted for downstream consumers.
--
-- Relationships:
--   integrations -> connected_repositories
--   connected_repositories -> webhook_receipts
--   webhook_receipts -> outbox_events
--
-- Referential integrity is enforced by composite keys; each repository and receipt
-- must belong to the same integration and organization. Outbox events also carry
-- immutable event identity and payload metadata checks.
CREATE TABLE connector.integrations (
    id TEXT PRIMARY KEY CHECK (btrim(id) <> ''),
    organization_id TEXT NOT NULL CHECK (btrim(organization_id) <> ''),
    provider TEXT NOT NULL CHECK (provider = 'github'),
    installation_id BIGINT NOT NULL CHECK (installation_id > 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISCONNECTED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, organization_id),
    UNIQUE (provider, installation_id)
);

CREATE TABLE connector.connected_repositories (
    integration_id TEXT NOT NULL,
    organization_id TEXT NOT NULL,
    repository_id TEXT NOT NULL CHECK (btrim(repository_id) <> ''),
    full_name TEXT NOT NULL CHECK (btrim(full_name) <> ''),
    default_branch TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISCONNECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integration_id, repository_id),
    UNIQUE (integration_id, organization_id, repository_id),
    FOREIGN KEY (integration_id, organization_id)
        REFERENCES connector.integrations (id, organization_id)
);

CREATE TABLE connector.webhook_receipts (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    integration_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    delivery_id TEXT NOT NULL CHECK (btrim(delivery_id) <> ''),
    provider_event_type TEXT NOT NULL CHECK (btrim(provider_event_type) <> ''),
    received_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACCEPTED'
        CHECK (status IN ('ACCEPTED', 'IGNORED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (integration_id, delivery_id),
    UNIQUE (id, organization_id, integration_id),
    FOREIGN KEY (integration_id, organization_id, repository_id)
        REFERENCES connector.connected_repositories (integration_id, organization_id, repository_id)
);

CREATE TABLE connector.outbox_events (
    event_id TEXT PRIMARY KEY CHECK (btrim(event_id) <> ''),
    receipt_id UUID NOT NULL,
    organization_id TEXT NOT NULL,
    integration_id TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (btrim(event_type) <> ''),
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    topic TEXT NOT NULL CHECK (btrim(topic) <> ''),
    partition_key TEXT NOT NULL CHECK (btrim(partition_key) <> ''),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CHECK ((status = 'PENDING' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)),
    CHECK ((payload ->> 'eventId') IS NOT DISTINCT FROM event_id),
    CHECK ((payload ->> 'organizationId') IS NOT DISTINCT FROM organization_id),
    CHECK ((payload -> 'source' ->> 'integrationId') IS NOT DISTINCT FROM integration_id),
    FOREIGN KEY (receipt_id, organization_id, integration_id)
        REFERENCES connector.webhook_receipts (id, organization_id, integration_id)
);

CREATE INDEX integrations_organization_idx ON connector.integrations (organization_id);
CREATE INDEX webhook_receipts_organization_idx
    ON connector.webhook_receipts (organization_id, received_at);
CREATE INDEX outbox_events_pending_idx
    ON connector.outbox_events (created_at, event_id) WHERE status = 'PENDING';
