-- A merge is a provider fact, independent of how many integrations or deliveries report it.
CREATE TABLE change_records (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL CHECK (btrim(organization_id) <> ''),
    provider TEXT NOT NULL,
    integration_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    repository_full_name TEXT NOT NULL,
    pull_request_number INTEGER NOT NULL CHECK (pull_request_number > 0),
    title TEXT,
    commit_sha TEXT NOT NULL,
    source_branch TEXT NOT NULL,
    target_branch TEXT NOT NULL,
    actor_type TEXT CHECK (actor_type IN ('HUMAN', 'BOT', 'AI', 'UNKNOWN')),
    actor_id TEXT,
    merged_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    source_event_id TEXT NOT NULL,
    trace_id TEXT,
    upstream_change_id TEXT,
    status TEXT NOT NULL CHECK (status = 'MERGED'),
    CHECK ((actor_type IS NULL) = (actor_id IS NULL)),
    UNIQUE (organization_id, provider, repository_id, pull_request_number),
    -- Allows receipts to reference a change without crossing organization ownership.
    UNIQUE (organization_id, id)
);
CREATE INDEX change_records_organization_timeline ON change_records (organization_id, merged_at DESC, id);
