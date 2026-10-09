# GitHub repository integration delivery plan

This plan breaks the README's V1 GitHub integration into reviewable, testable
deliverables. It follows the platform's intended order: foundation, canonical
events, then GitHub ingestion. Each deliverable should be merged independently
where practical; avoid building later stages before their prerequisites exist.

## Current connector progress

The connector persistence foundation is implemented: Compose includes PostgreSQL
18.6, Flyway owns the `connector` schema, and Spring JDBC stores organization-owned
GitHub installations, selected repositories, webhook receipts, and canonical JSONB
outbox records. Accepted merged-change receipts and outbox records commit atomically;
integration/delivery uniqueness absorbs concurrent redelivery. Real PostgreSQL
tests cover migrations, ownership, disconnect/revocation, duplicates, and rollback.
The HTTP processor verifies raw-byte HMAC before parsing, resolves the internal
organization through the stored installation binding, checks the active selected
repository, and commits supported merges before returning `202`. Verified
unsupported activity is ignored; invalid signatures/payloads and unconnected
repositories are rejected. A scheduled relay leases due events, publishes their
stored JSON envelopes as registered Avro to Kafka, and marks success after acknowledgement. Retry
backoff and lease expiry survive restart; downstream consumers must deduplicate.
Real PostgreSQL, Kafka, and Schema Registry tests cover the complete HTTP-to-Avro
publication flow, redelivery, compatibility rejection, and worker recovery.
See [the persistence diagram and API](../../backend/README.md#connector-persistence)
and [relay behavior](../../backend/README.md#outbox-relay).

This is part of Deliverables 1, 2, 4, and 7, not completion of the GitHub integration.
The backend parent, shared Avro catalog, and Schema Registry are implemented.
`PullRequestMerged` is canonical; historical `CodeChangeMerged` outbox records
upcast into the Avro stream while preserving identity. Opened-PR and commit
schemas are ready, and their webhook adapters remain planned. Change Service,
authorized GitHub App setup APIs, installation lifecycle reconciliation, downstream consumers, and
the first live UI timeline remain to be built. `503` now means durable database
processing failed; a Kafka outage leaves accepted events pending for relay retry.

## GitHub is hosted; ChangeGuard runs locally during development

The initial integration targets **GitHub.com**, which is already hosted by
GitHub. ChangeGuard does not install or run a GitHub server. A GitHub App is
created in GitHub's online developer settings and installed by an authorized
user into the GitHub account or organization whose repositories ChangeGuard
should track. This app installation grants the approved access; it is distinct
from installing GitHub software locally.

During local development, ChangeGuard and its dependencies run on the
developer's machine. Outbound calls to GitHub.com work as normal. GitHub
webhooks, however, need a publicly reachable HTTPS callback. For ordinary
development, use sanitized webhook fixtures and mocked GitHub API responses.
For an explicitly configured GitHub App sandbox test, expose only the local
webhook endpoint through an approved HTTPS tunnel and use a test repository.
Never expose the full local platform or use production credentials for this
purpose.

## Product outcome

A user can connect a GitHub repository to ChangeGuard. New pull request and
commit activity is authenticated, deduplicated, converted to provider-neutral
code events, stored, and shown in the ChangeGuard UI with its repository and
actor attribution.

For the first release, ChangeGuard observes changes only. It does not write to
GitHub, install workflows, or make repository changes.

## Alignment with the README

- V1 starts with ingestion, correlation, analysis, and visibility; production
  write actions are deferred.
- The Connector Service receives webhooks, verifies authenticity, normalizes
  provider payloads, deduplicates events, and publishes canonical events.
- The canonical code event types include `PullRequestOpened`,
  `PullRequestMerged`, and `CommitCreated`.
- Event envelopes carry organization and integration identity. Events use the
  shared, versioned event contract and the code-event topic family.
- Repository, pull request, and commit records belong to the Change Service.
- REST APIs use the `/api/v1` prefix; integration credentials are never stored
  in source control or exposed to the browser.
- The README's Phase 3 GitHub completion criterion is that a merged pull
  request becomes a canonical code event and is visible in the UI.

## Recommended MVP decisions

Use a GitHub App installation flow rather than asking users for personal access
tokens. Request only the read permissions needed to discover installations and
repositories and read pull request and commit metadata. Confirm the exact
permission set against the GitHub API endpoints selected during implementation.

Use webhook-first ingestion for the MVP: start tracking new activity after a
repository is connected. Do not silently backfill repository history. If
historical import is required, define its time range, volume limits, and user
expectations as a separate deliverable before implementation.

Do not store installation access tokens long-term. Keep the GitHub App private
key and webhook secret in the configured secrets manager; mint short-lived
installation tokens only when an API call is needed. Persist integration and
repository metadata, not credentials.

## Delivery sequence

### 0. Confirm integration contract and threat boundaries

**Deliverable:** a short architecture decision record covering GitHub App
ownership, required read permissions, GitHub.com as the initial hosted
provider, install scope, local webhook testing approach, and webhook-first
tracking behavior.

**Acceptance criteria**

- The user journey and organization/repository ownership model are written
  down.
- GitHub.com is confirmed as the initial target; no locally hosted GitHub
  server is required.
- The permission list is mapped to concrete GitHub API operations and webhook
  events.
- The MVP explicitly excludes repository writes and unbounded history import.
- Secrets, installation tokens, webhook validation, and organization
  isolation have named owners and storage boundaries.

**Tests / review:** security and product review of the decision record before
credential or webhook implementation begins.

### 1. Establish the backend and local platform prerequisites

**Deliverable:** create the backend Maven parent and the smallest runnable
Connector Service / Change Service foundation, plus the local PostgreSQL,
Kafka, and Schema Registry services required by the README. Keep service data
ownership explicit even if services share a local PostgreSQL cluster.

**Acceptance criteria**

- Services start through the documented local Compose workflow.
- Health endpoints report readiness and missing configuration fails
  explicitly.
- PostgreSQL and Kafka are reachable from services on the Compose network.
- No GitHub secret is committed or required to start the rest of the platform.

**Tests:** Maven compile/unit tests; Compose startup/readiness smoke test;
service-to-PostgreSQL and producer-to-Kafka integration tests.

**Depends on:** README V1 Delivery Plan Phase 1.

### 2. Define and validate the canonical code event contract

**Status: implemented schemas and merged-PR publication.** The
[shared module](../../backend/event-contracts/README.md) generates Avro records,
checks released compatibility, and registers the catalog under `BACKWARD_TRANSITIVE`.
[Schema Registry](../../backend/docker/schema-registry/README.md) persists history
in Kafka and rejects incompatible registrations. Other code-event adapters are
tracked in the intake deliverable below.

**Deliverable:** versioned Avro schemas for `PullRequestOpened`,
`PullRequestMerged`, and `CommitCreated`, using the common event envelope.
Include provider, integration ID, organization ID, repository identity,
provider event/delivery identity, actor, occurrence time, and canonical
payload fields. Keep provider-specific raw payloads out of the canonical
contract unless there is a documented need.

**Acceptance criteria**

- Schemas register and pass the chosen compatibility policy.
- Representative GitHub payloads map to the same canonical shape regardless
  of provider-specific naming.
- Invalid or unsupported payloads fail validation with an actionable error.
- Event keys support stable ordering for a repository timeline.

**Tests:** schema compatibility tests; serialization round trips; mapping
unit tests using sanitized fixture payloads.

**Depends on:** Deliverable 1 and README V1 Delivery Plan Phase 2.

### 3. Create GitHub App configuration and safe installation state

**Deliverable:** configure the GitHub App and implement the start/callback
flow with signed, single-use state, expiration, and organization-aware
authorization. Keep private key and webhook secret in server-side secret
configuration. Never send these values to the frontend.

**Acceptance criteria**

- The GitHub App requests only the approved read permissions.
- Installation callbacks reject missing, expired, replayed, or mismatched
  state.
- A user can only connect an installation to an organization they are
  authorized to manage.
- Secrets are redacted from logs and errors; installation tokens are not
  persisted as durable credentials.

**Tests:** state validation and replay unit tests; authorization tests;
secret-redaction tests; callback integration tests with a mocked GitHub API.

**Depends on:** Deliverable 0 and backend authentication/organization context.

### 4. Persist integration and selected repository metadata

**Deliverable:** ChangeGuard API endpoints and persistence for GitHub
installations and connected repositories. Define explicit connect, list,
disconnect, and installation-revoked behavior. Repository credentials are
not part of the persisted model.

**Acceptance criteria**

- Records have organization ownership, GitHub installation identity, stable
  repository identity, display name, default branch when available, status,
  and timestamps.
- Repository selection is limited to repositories available to that
  installation.
- Duplicate connect requests are idempotent.
- Disconnecting or uninstalling the GitHub App stops processing future
  events for the affected installation/repositories.

**Tests:** repository/service unit tests; database migration tests;
organization-isolation integration tests; duplicate connect and revoke
tests.

**Depends on:** Deliverable 3.

### 5. Add the repository connection experience

**Deliverable:** UI entry point to install/connect the GitHub App, choose
repositories, show connection status, and disconnect. The frontend talks only
to ChangeGuard's `/api/v1` integration APIs.

**Acceptance criteria**

- Loading, empty, denied, success, and API failure states are visible.
- The UI never receives or stores GitHub private keys or installation
  access tokens.
- A user can tell which organization and repositories are connected.
- Revoked/disconnected installations are clearly identified.

**Tests:** component tests for each state; route-level integration test for a
successful connect/list/disconnect flow using mocked ChangeGuard APIs.

**Depends on:** Deliverable 4 and the existing frontend shell.

### 6. Receive and authenticate GitHub webhooks

**Deliverable:** a Connector Service endpoint for the approved initial
webhook events, including installation/repository changes, relevant pull
request actions, and pushes. Verify `X-Hub-Signature-256` against the exact
request bytes before parsing or processing the body.

**Acceptance criteria**

- Missing, malformed, and invalid signatures are rejected before payload
  processing.
- Signature comparison is constant-time and the webhook secret is selected
  from trusted server configuration.
- Unsupported event/action combinations are handled explicitly and do not
  create canonical events.
- Responses are fast and do not wait for downstream timeline/UI work.
- Payload contents, signatures, and secrets are not written to ordinary
  application logs.

**Tests:** valid/invalid signature tests; raw-body handling tests; supported
and unsupported event/action tests; HTTP contract tests.

**Depends on:** Deliverables 1 and 3.

### 7. Make webhook intake durable and idempotent

**Deliverable:** persist webhook receipt identity and processing state before
acknowledging successful intake. Use GitHub's delivery ID plus installation
context for deduplication. Ensure persistence and event publication use the
README's transactional-outbox approach rather than a database-write/Kafka-send
dual write.

**Acceptance criteria**

- Repeated delivery IDs do not create duplicate code events or records.
- A crash after receipt but before publication can be recovered.
- Failed validation/normalization is observable and cannot be mistaken for a
  successful canonical event.
- Retryable failures and permanent failures have distinct handling; permanent
  failures are inspectable and do not retry forever.

**Tests:** duplicate-delivery tests; outbox recovery tests; transaction
rollback tests; Kafka/PostgreSQL integration tests; retry/dead-letter tests.

**Depends on:** Deliverables 2 and 6.

### 8. Normalize pull request and commit activity

**Deliverable:** map supported pull request actions and pushes to canonical
code events. Map repository, pull request, commit, author/actor, timestamps,
base/head references, merge state, and stable provider IDs. Treat a merged
pull request as `PullRequestMerged`; do not infer a merge from unrelated
actions.

**Acceptance criteria**

- Re-delivered or reordered provider notifications do not produce an
  inconsistent current pull request state.
- GitHub node IDs / repository IDs and full commit SHAs are retained as
  provider identifiers; display names are not used as keys.
- Missing optional fields are represented according to the schema, not
  replaced with fabricated values.
- Provider-specific details remain behind the connector boundary.

**Tests:** fixture-driven unit tests for opened, synchronized, reopened,
closed-unmerged, merged, and push events; malformed payload tests; ordering
and replay tests.

**Depends on:** Deliverable 7.

### 9. Store canonical change records and expose timeline APIs

**Deliverable:** Change Service consumers persist repository, pull request,
and commit records, relate them, and expose read APIs for connected
repositories and their change activity.

**Acceptance criteria**

- Reprocessing the same canonical event is idempotent.
- A merged pull request and its commits can be fetched through versioned
  `/api/v1` endpoints.
- Data is isolated by organization and repository integration.
- Eventual-consistency status is clear when a webhook is accepted but the
  projection has not finished.

**Tests:** consumer unit tests; Kafka-to-database integration tests;
organization isolation tests; API contract tests; replay/rebuild tests.

**Depends on:** Deliverables 2 and 8.

### 10. Complete the first end-to-end GitHub slice

**Deliverable:** demonstrate installation, repository selection, webhook
receipt, canonical event publication, Change Service persistence, and UI
visibility in local and CI environments.

**Acceptance criteria**

- A test installation or signed fixture webhook for a merged PR produces one
  canonical `PullRequestMerged` event.
- The event has organization, integration, repository, actor, and occurrence
  time attribution.
- The merged PR and related commits appear in the UI without manually editing
  the database.
- Duplicate delivery, invalid signature, revoked installation, and
  downstream outage scenarios have expected outcomes.
- Logs, metrics, and alerts cover webhook acceptance/rejection, processing
  failures, outbox age, consumer lag, and GitHub API failures.

**Tests:** automated end-to-end test using local Kafka/PostgreSQL and signed
fixtures; manual GitHub App sandbox checklist; security scan and operational
readiness review.

**Depends on:** Deliverables 3–9.

## Cross-cutting requirements

- Keep all integration APIs under `/api/v1`.
- Enforce organization isolation at API, persistence, and event-consumer
  boundaries.
- Verify webhook authenticity before trusting any payload identifier.
- Apply rate limits to public webhook and installation endpoints.
- Use least-privilege GitHub App permissions; no repository write access in
  this MVP.
- Keep secrets server-side, encrypted at rest, redacted in logs, and rotatable.
- Capture audit events for connect, repository selection, disconnect, and
  installation revocation.
- Instrument intake latency, signature failures, duplicate deliveries,
  GitHub API errors, outbox age, publish failures, and consumer lag.
- Test with sanitized fixtures and mocked GitHub API responses by default;
  reserve live GitHub.com App tests for an explicitly configured sandbox. Use
  an approved HTTPS tunnel only to forward the local webhook endpoint when a
  live callback test is needed.

## Out of scope for this first integration

- GitHub Actions workflow execution and build-result ingestion.
- Deployment tracking and production impact correlation.
- GitHub write operations, checks/status publishing, PR comments, or
  auto-generated workflows.
- Historical repository backfill beyond the webhook-first MVP.
- GitLab, Bitbucket, and GitHub Enterprise Server until separately scoped.

## README phase mapping

| This plan | README V1 delivery plan |
| --- | --- |
| Deliverable 1 | Phase 1 — Platform foundation |
| Deliverable 2 | Phase 2 — Canonical event system |
| Deliverables 3–10 | Phase 3 — GitHub integration |
| Deliverables 8–10 | Phase 4 prerequisite: canonical PR/commit records for CI linking |

## First implementation slice

Start with Deliverables 0–2, then implement Deliverables 3–6 as a thin
vertical slice: install the hosted GitHub App into a test account or
organization, select one repository, accept only verified webhook deliveries,
and prove a merged PR can be converted into the versioned canonical code-event
contract. No GitHub server runs locally. Add durable publication and UI
projection in Deliverables 7–10 before calling repository tracking complete.
