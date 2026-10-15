-- Verified provider account metadata is optional for legacy, internally provisioned integrations.
ALTER TABLE connector.integrations
    ADD COLUMN connection_version BIGINT NOT NULL DEFAULT 0 CHECK (connection_version >= 0),
    ADD COLUMN github_account_id BIGINT CHECK (github_account_id > 0),
    ADD COLUMN github_account_login TEXT,
    ADD COLUMN github_account_type TEXT CHECK (github_account_type IN ('Organization', 'User')),
    ADD CONSTRAINT github_account_metadata_complete CHECK (
        (github_account_id IS NULL AND github_account_login IS NULL AND github_account_type IS NULL)
        OR (github_account_id IS NOT NULL AND github_account_login IS NOT NULL
            AND btrim(github_account_login) <> '' AND github_account_type IS NOT NULL));

-- Only a hash of the opaque, signed callback state is stored. Its owner comes from
-- authenticated server-side authorization, not from callback query parameters.
CREATE TABLE connector.github_installation_states (
    state_hash TEXT PRIMARY KEY CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    organization_id TEXT NOT NULL CHECK (btrim(organization_id) <> ''),
    administrator_name TEXT NOT NULL CHECK (btrim(administrator_name) <> ''),
    github_account_id BIGINT NOT NULL CHECK (github_account_id > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX github_installation_states_expiry_idx
    ON connector.github_installation_states (expires_at);

-- Installation events have no individual repository. Use the same receipt ledger
-- and delivery uniqueness keys as merged PRs, while retaining tenant integrity.
ALTER TABLE connector.webhook_receipts
    ALTER COLUMN repository_id DROP NOT NULL,
    ADD COLUMN provider_action TEXT,
    ADD CONSTRAINT webhook_receipts_integration_owner_fk
        FOREIGN KEY (integration_id, organization_id) REFERENCES connector.integrations (id, organization_id),
    ADD CONSTRAINT webhook_receipts_repository_or_lifecycle CHECK (
        (repository_id IS NOT NULL AND provider_action IS NULL)
        OR (repository_id IS NULL AND provider_action IS NOT NULL AND (
            (provider_event_type = 'installation' AND provider_action IN ('deleted', 'suspend'))
            OR (provider_event_type = 'installation_repositories' AND provider_action = 'removed'))));
