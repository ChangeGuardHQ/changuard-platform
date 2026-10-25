# Change Service

Projects the canonical `PullRequestOpened`, `PullRequestMerged` and `CommitCreated`
V1 Avro events into PostgreSQL changes, durable timelines and organization-scoped
read APIs. The implementation and follow-up PRs are described in the
[delivery steps](BUSINESS_LOGIC.md). Build/deployment correlation and outgoing
derived events remain later work.

## Run with Docker Compose

From the repository root:

```sh
docker compose up --build --wait change-service
curl --fail http://localhost:8082/actuator/health/readiness
curl --fail --user changeguard:change-me 'http://localhost:8082/api/v1/changes?organizationId=local'
```

Compose starts PostgreSQL, Kafka and Schema Registry before the service. The
shared database uses `change` for this service and `connector` for the producer.
Flyway applies the real domain migrations before Hibernate validates mappings.
The runtime uses a non-root user, read-only filesystem, and writable temporary
storage. Stop it with `docker compose down`; named volumes retain data.

Compose binds the development reader to organization `local`. Set
`CHANGE_ORGANIZATION_ID` to the same trusted organization used by the connector's
integration. Set `CHANGE_ORGANIZATION_READERS` to authenticated usernames allowed
to query it. Override Basic credentials using `CHANGE_SERVICE_USER` and
`CHANGE_SERVICE_PASSWORD`. Changing query parameters cannot grant organization
access. An empty binding grants no access when running outside Compose.

## Run locally

Start `postgres kafka schema-registry` through Compose. From this service directory:

```sh
./mvnw -f ../../pom.xml -pl event-contracts -am -DskipTests install
./mvnw spring-boot:run
```

The first command installs the shared generated contract for the standalone
launch. Set `SPRING_SECURITY_USER_NAME`, `SPRING_SECURITY_USER_PASSWORD`,
`CHANGE_ORGANIZATION_ID`, and `CHANGE_ORGANIZATION_READERS` for authenticated
reads. Without explicit credentials, Boot creates its default local account.

## Configuration

Defaults live in [application.yml](src/main/resources/application.yml).

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | HTTP port. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/changeguard?currentSchema=change` | JDBC URL. |
| `DB_USERNAME` / `DB_PASSWORD` | `changeguard` / `changeguard-local` | Development database credentials. |
| `DB_POOL_SIZE` | `10` | JDBC pool maximum. |
| `FLYWAY_ENABLED` | `true` | Apply reviewed migrations before JPA validation. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker addresses. |
| `KAFKA_CONSUMER_GROUP` | `change-service-group` | Durable projection consumer group. |
| `CODE_EVENTS_TOPIC` | `changeguard.code-events.v1` | Connector's canonical Avro topic. |
| `CODE_EVENTS_DLT_TOPIC` | `changeguard.change-service.code-events.v1.dlt` | Durable quarantine; must differ from source. |
| `CODE_EVENTS_RETRIES` / `CODE_EVENTS_RETRY_INTERVAL` | `3` / `1s` | Bounded processing retry policy (0..10 retries, interval 0..10s). |
| `SCHEMA_REGISTRY_URL` | `http://localhost:8083` | Writer schema lookup. |
| `KAFKA_LISTENER_AUTO_STARTUP` | `true` | Set false to pause projection without changing offsets. |
| `CHANGE_ORGANIZATION_ID` | empty | Trusted organization binding; Compose defaults to `local`. |
| `CHANGE_ORGANIZATION_READERS` | empty | Comma-separated authenticated usernames; Compose binds its Basic user. |

Configure `changeguard.api.organizations` directly for multiple trusted bindings.
Compose uses internal container addresses independently of host port overrides.
Kafka security properties can be supplied through standard Boot configuration.

## Projection and replay semantics

- A pull-request change is unique by organization, provider, repository ID and PR
  number. A standalone commit is unique by organization/provider/repository/SHA.
  Multiple deliveries or integrations retain one UUID per business identity.
- The change and event receipt commit in one database transaction. Event IDs are
  scoped by organization; a fingerprint rejects reuse with different facts.
  The change, business timeline fact and receipt commit together; any failure
  rolls back all three. V3 backfills existing merges without changing their IDs.
- A PR progresses from OPEN to MERGED using the same UUID. A merge may arrive
  first; a later opening adds its occurrence and head SHA without reversing the
  merge state, merge attribution, or activity time. Contradictory opening/merge
  facts are rejected. Provider opening time cannot follow provider merge time.
- A commit has kind COMMIT and status COMMITTED; it never claims a PR, even when
  its SHA matches a PR head or merge commit. Branch equality and upstream change
  IDs are insufficient linkage evidence. Merge and commit occurrence times are
  immutable. Redelivery receipt time does not alter event identity.
- A merge promotes the PR projection's title, merge SHA and actor; its opening
  keeps its own immutable timeline fact. Duplicate facts retain the first accepted
  metadata. Correlation IDs never select or overwrite this service's change ID.
- The listener validates the shared contract and repository partition key,
  then returns only after database commit. Record acknowledgments and disabled
  auto-commit prevent a failed transaction from advancing the Kafka offset.
- Invalid Avro, conflicting facts, unsupported event types/versions and database
  constraint violations go directly to the dedicated dead-letter topic. Temporary
  database failures get three retries, one second apart, then quarantine. The
  original offset advances only after the broker acknowledges the DLT write;
  unavailable quarantine retains the offset for another attempt. DLT duplicates
  remain possible after a crash between publish and offset commit.
  Unknown future contracts remain quarantined until a matching handler is deployed.
- Health GET endpoints are public and omit anonymous component details. Other
  reads require Basic authentication. HTTP sessions are disabled and CSRF remains
  enabled. Readiness checks PostgreSQL, independently of asynchronous Kafka progress.

## Read API

- `GET /api/v1/changes?organizationId=org-one&page=0&size=20` lists records with
  optional `repositoryId`. Size is 1..100 and page is 0..10000. Sort order is
  activity time descending, then UUID ascending for a stable tie-breaker. Activity
  time is merge time for MERGED, opening time for OPEN, and commit time for COMMITTED.
- `GET /api/v1/changes/{id}?organizationId=org-one` returns a scoped change.
- `GET /api/v1/changes/{id}/timeline?organizationId=org-one` returns stored lifecycle
  facts ordered by provider occurrence time, type and original event ID. Each fact
  retains its own actor; delivery retries never add timeline entries.

Missing authentication returns 401, an unbound organization returns 403, and
an ID outside the authorized organization returns 404. Invalid query values
return 400. There is no HTTP mutation endpoint for provider-owned facts. `kind` distinguishes
PULL_REQUEST and COMMIT. `pullRequestNumber`, `openedAt`, `headCommitSha`, `mergedAt`
and branch fields are nullable when that fact does not apply or has not been
observed. `activityAt` is always present. A PR merge never invents an opening time
or head SHA; the opening contract supplies those separately.

## Verify

Build the three hardened integration images from the repository root first:

```sh
docker build --tag changuard-postgres:18.6-security.2 backend/docker/postgres
docker build --tag changuard-kafka:4.2.2-security.3 backend/docker/kafka
docker build --file backend/docker/schema-registry/Dockerfile --tag changuard-schema-registry:8.3.2-security.3 backend
sh backend/services/change-service/mvnw -f backend/pom.xml -pl services/change-service -am verify
```

Tests use disposable PostgreSQL, Kafka and Registry containers, plus an embedded
broker for focused transport checks. They cover migrations, tenant isolation,
concurrent deliveries, rollback, authorization, pagination, specific Avro decoding,
real broker replay, lossless quarantine, and retained offsets when quarantine fails. No external provider
credentials or existing databases are needed. Generated Avro sources remain in
`target`; only reviewed schemas and source files are versioned.

## Recover a quarantined event

Provision `CODE_EVENTS_DLT_TOPIC` before deployment with broker replication,
`min.insync.replicas`, retention, and ACLs matching the durability requirements
of the source topic. Grant change-service write access and operators read access;
production deployments must not depend on broker topic auto-creation. Original
source topic, partition, offset and failure class are retained in Kafka headers.
Original keys and Registry-framed values are preserved as bytes, including invalid
Avro and tombstones. Retain the source schemas while replay remains possible.

Repair the database, deploy the missing handler, or correct the original producer
first. Invalid bytes cannot become valid through replay alone. Inspect one chosen
DLT offset with the packaged command; the default is a dry run and prints no payload:

```sh
java -Dloader.main=com.changeguard.change.messaging.DeadLetterReplay \
  -cp backend/services/change-service/target/change-service-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --bootstrap-servers=localhost:9092 \
  --dead-letter-topic=changeguard.change-service.code-events.v1.dlt \
  --source-topic=changeguard.code-events.v1 --partition=0 --offset=0
```

Repeat with `--execute` to publish that single record back to its original source
partition. Optional `--client-properties=/private/path/kafka.properties` supplies
TLS/SASL client configuration without embedding secrets in command arguments or
version control. The tool verifies source provenance, strips stale failure headers,
waits for the broker acknowledgment, and never changes DLT or projection consumer
group offsets. Repeated execution is safe through transactional event receipts;
quarantine records remain until retention expires, so record the replayed offset.

## Observe projection progress

`GET /actuator/health/projection` checks whether an enabled consumer is running,
paused, or waiting for partition assignment. Intentional configuration with
`KAFKA_LISTENER_AUTO_STARTUP=false` reports UP with `enabled=false`. Details require
authentication. This probe checks container state; use lag and error metrics to
assess progress or broker outages. The read API readiness probe continues to check
the database independently of projection availability.

The authenticated `/actuator/prometheus` endpoint exposes:

- `changeguard_change_ingestion_total{result="applied|duplicate"}`: committed event
  receipts, including already processed event IDs. Distinct delivery IDs are
  applied receipts even when they resolve to the same business change.
- `changeguard_change_delivery_failures_total` and `..._retries_total`: failed
  delivery attempts, including deserialization failures before listener invocation.
- `changeguard_change_delivery_quarantined_total`: broker-acknowledged recovery.
  `..._recovery_failures_total` counts failed quarantine attempts.
- `changeguard_change_consumer_enabled` and `..._running`: expected and actual
  listener state. Alert when enabled is 1 and running remains 0.
- Native `kafka_consumer_fetch_manager_records_lag_max` and consumption metrics:
  inspect backlog per assigned partition. These are fetched-position metrics;
  processing retries can keep committed progress behind the fetch position.

Alert on sustained delivery/recovery failures, any quarantine growth, stopped
consumers and increasing lag. Application counters reset on process restart;
Prometheus rates must account for resets. Labels never contain organization IDs,
repository IDs, event IDs or payloads. Database rollback does not increment the
successful-ingestion counter.

## Enable additional GitHub producers

Deploy this consumer and allow Flyway V4 to migrate existing PR rows first; UUIDs,
receipts and merge timelines are preserved. Then enable
`GITHUB_CODE_LIFECYCLE_ENABLED=true` in the connector and subscribe the GitHub App
to **Pull request** and **Push** events (with repository contents read permission
for push delivery). Existing selected-repository and trusted-ownership checks apply
to both new adapters. A push stores one receipt and one deterministic event per
included commit; the complete batch rolls back if any outbox write fails.

The connector ignores deleted-branch and tag pushes and does not backfill provider
history. A push payload may include at most 2048 commits; only those delivered
commits are represented. Existing historical code can be replayed through the
consumer after its contracts and provider facts are available.

GitHub documents push permission requirements and the payload's commit limit in
[Webhook events and payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads#push).
