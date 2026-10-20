-- Store business facts separately from delivery receipts; duplicate deliveries do not add timeline entries.
CREATE TABLE change_timeline (
    organization_id TEXT NOT NULL,
    change_id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (btrim(event_type) <> ''),
    fact_key TEXT NOT NULL CHECK (btrim(fact_key) <> ''),
    event_id TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    commit_sha TEXT NOT NULL,
    actor_type TEXT CHECK (actor_type IN ('HUMAN', 'BOT', 'AI', 'UNKNOWN')),
    actor_id TEXT,
    integration_id TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, change_id, event_type, fact_key),
    CHECK ((actor_type IS NULL) = (actor_id IS NULL)),
    FOREIGN KEY (organization_id, change_id) REFERENCES change_records (organization_id, id)
);
CREATE INDEX change_timeline_order ON change_timeline (organization_id, change_id, occurred_at, event_type, event_id);

-- Preserve the original accepted merge fact for databases already populated by V1/V2.
INSERT INTO change_timeline (organization_id, change_id, event_type, fact_key, event_id,
        occurred_at, commit_sha, actor_type, actor_id, integration_id, recorded_at)
SELECT organization_id, id, 'PullRequestMerged', 'merge', source_event_id,
       merged_at, commit_sha, actor_type, actor_id, integration_id, created_at
FROM change_records;
