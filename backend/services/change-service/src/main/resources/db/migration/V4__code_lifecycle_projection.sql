-- Extend existing PR rows in place; their UUIDs, receipts and persisted merge facts remain stable.
ALTER TABLE change_records ADD COLUMN kind TEXT NOT NULL DEFAULT 'PULL_REQUEST';
ALTER TABLE change_records ADD COLUMN opened_at TIMESTAMPTZ;
ALTER TABLE change_records ADD COLUMN head_commit_sha TEXT;
ALTER TABLE change_records ADD COLUMN activity_at TIMESTAMPTZ;
UPDATE change_records SET activity_at = merged_at;
ALTER TABLE change_records ALTER COLUMN activity_at SET NOT NULL;
ALTER TABLE change_records ALTER COLUMN pull_request_number DROP NOT NULL;
ALTER TABLE change_records ALTER COLUMN merged_at DROP NOT NULL;
ALTER TABLE change_records ALTER COLUMN source_branch DROP NOT NULL;
ALTER TABLE change_records ALTER COLUMN target_branch DROP NOT NULL;
ALTER TABLE change_records DROP CONSTRAINT change_records_status_check;

-- Reject impossible combinations even when a caller bypasses application lifecycle validation.
ALTER TABLE change_records ADD CONSTRAINT change_records_lifecycle CHECK (
    (kind = 'PULL_REQUEST' AND pull_request_number IS NOT NULL
        AND source_branch IS NOT NULL AND target_branch IS NOT NULL
        AND ((status = 'OPEN' AND opened_at IS NOT NULL AND head_commit_sha IS NOT NULL AND merged_at IS NULL)
            OR (status = 'MERGED' AND merged_at IS NOT NULL)))
    OR (kind = 'COMMIT' AND status = 'COMMITTED' AND pull_request_number IS NULL
        AND merged_at IS NULL AND opened_at IS NULL AND head_commit_sha IS NULL AND target_branch IS NULL)
);
ALTER TABLE change_records ADD CONSTRAINT change_records_occurrence_order
    CHECK (opened_at IS NULL OR merged_at IS NULL OR opened_at <= merged_at);
-- NULL PR numbers do not collide with the existing PR natural-key uniqueness constraint.
CREATE UNIQUE INDEX change_records_commit_identity
    ON change_records (organization_id, provider, repository_id, commit_sha) WHERE kind = 'COMMIT';
CREATE INDEX change_records_activity ON change_records (organization_id, activity_at DESC, id);
