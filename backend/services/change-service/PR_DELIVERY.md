# Change-service follow-up PRs

Four local branches contain one independently tested commit each. They are stacked:
review each PR against the preceding branch so its diff contains only that step.
Pushes and GitHub PR creation are left to the repository owner.

| Order | Head branch | PR base | Suggested title |
| --- | --- | --- | --- |
| 1 | `feature/change-consumer-recovery` | `main` | Recover failed change events through durable quarantine and replay |
| 2 | `feature/change-consumer-visibility` | `feature/change-consumer-recovery` | Expose change projection health and delivery metrics |
| 3 | `feature/change-persistent-timeline` | `feature/change-consumer-visibility` | Persist scoped change timelines and backfill existing merge facts |
| 4 | `feature/change-code-lifecycle` | `feature/change-persistent-timeline` | Project opened pull requests and commits through the durable connector pipeline |

Push only the intended branch names from the repository root:

```sh
# Explicit refs avoid pushing main or unrelated local branches.
git push -u origin feature/change-consumer-recovery
git push -u origin feature/change-consumer-visibility
git push -u origin feature/change-persistent-timeline
git push -u origin feature/change-code-lifecycle
```

Merge in table order. After a predecessor merges, check the next PR's base and diff
before retargeting it to `main`. A squash merge may require rebasing the remaining
stack to remove predecessor commits; do not blindly force-push shared branches.

## PR 1 description

A failed code record previously stopped projection. Temporary processing failures
now get bounded retries; permanent or exhausted failures publish their exact source
key/value bytes to a dedicated DLT. Source offsets advance only after acknowledged
quarantine. Failed DLT publication retains the source record. The packaged,
single-offset replay tool validates source provenance and defaults to dry run.

Validation: 46 reactor tests passed, followed by broker tests of actual replay in
dry-run and execute modes. Coverage includes temporary database failure, invalid
Avro, later-record progress, byte preservation and failed quarantine publication.

Deployment: provision the DLT with appropriate replication, retention and ACLs.
See the [recovery guide](README.md#recover-a-quarantined-event).

## PR 2 description

Operators can detect stopped/paused projection independently of the read API's
database readiness. Add a dedicated projection health group, enabled/running
gauges, commit-aware ingestion counters, delivery/recovery counters and native
Kafka fetch/lag metrics. Application metrics use fixed labels without tenant,
repository or event IDs.

Validation: 51 reactor tests passed, including health states, public probe detail
redaction, rollback-safe counters and container-driven recovery observations.
See the [observability guide](README.md#observe-projection-progress).

## PR 3 description

The timeline endpoint now reads persisted business facts instead of constructing
a merge entry from the current projection. Projection, timeline and event receipt
writes share one transaction. Flyway V3 backfills existing merges with their
original event IDs, actors and occurrence times. Tenant foreign keys protect
timeline ownership, and repeated deliveries do not create new business entries.

Validation: 54 reactor tests passed, including populated V2 migration, tenant
isolation and rollback when timeline or receipt persistence fails.

## PR 4 description

Support the released `PullRequestOpened` and `CommitCreated` contracts. PRs advance
from OPEN to MERGED under row locks; late opening facts enrich history without
downgrading the projection. Standalone commits retain their own scoped SHA identity
and are not linked to PRs using ambiguous branch or SHA guesses. Flyway V4 retains
existing PR UUIDs, receipts and merge history.

The connector adds HMAC-verified opening/push adapters, trusted installation and
selected-repository checks, stable per-commit IDs and atomic multi-commit outbox
storage. Its new producer flag defaults to false for an ordered rollout. The read
API adds explicit kind/opening/activity fields and permits null PR/merge fields
when they do not apply.

Validation: the combined contracts/connector/change reactor passed 364 tests.
Coverage includes signed webhook-to-Registry/Kafka publication, late opening and
duplicate commit consumption, concurrent lifecycle transitions, transactional
rollback, legacy merge publication, migrations and organization-scoped API reads.
Both Compose configurations validate; the optional GitHub override uses
credential-free placeholder settings during validation.

Deployment: deploy the consumer and V4 migration before enabling
`GITHUB_CODE_LIFECYCLE_ENABLED=true`. Configure GitHub App subscriptions and keep
the DLT/replay procedure available. Build/deployment correlation and provider
history backfill remain outside these PRs. See the
[producer rollout guide](README.md#enable-additional-github-producers).
