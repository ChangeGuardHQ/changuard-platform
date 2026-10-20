# Change Service

Projects the connector's canonical `PullRequestMerged` V1 Avro events into
PostgreSQL merge records and organization-scoped read APIs. The implementation
is split into four reviewable [delivery steps](BUSINESS_LOGIC.md). Build,
deployment, opened-PR and standalone-commit projections remain later work.

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

- A change is unique by organization, provider, repository ID, and PR number.
  Multiple deliveries or integrations reporting the same merge retain one UUID.
- The change and event receipt commit in one database transaction. Event IDs are
  scoped by organization; a fingerprint rejects reuse with different facts.
  A receipt failure rolls back a newly inserted projection as well.
- Merge commit and provider occurrence time are immutable. The first accepted
  title, repository name and actor metadata remain attached to the merge.
  Redelivery receipt time does not alter identity. Correlation IDs never select
  or overwrite this service's change ID.
- The listener validates the shared contract and repository partition key,
  then returns only after database commit. Record acknowledgments and disabled
  auto-commit prevent a failed transaction from advancing the Kafka offset.
- Invalid Avro, conflicting facts, unsupported event types/versions and database
  constraint violations go directly to the dedicated dead-letter topic. Temporary
  database failures get three retries, one second apart, then quarantine. The
  original offset advances only after the broker acknowledges the DLT write;
  unavailable quarantine retains the offset for another attempt. DLT duplicates
  remain possible after a crash between publish and offset commit.
  Implement new handlers before enabling opened-PR/commit producers on the topic.
- Health GET endpoints are public and omit anonymous component details. Other
  reads require Basic authentication. HTTP sessions are disabled and CSRF remains
  enabled. Readiness checks PostgreSQL, independently of asynchronous Kafka progress.

## Read API

- `GET /api/v1/changes?organizationId=org-one&page=0&size=20` lists records with
  optional `repositoryId`. Size is 1..100 and page is 0..10000. Sort order is
  merge time descending, then UUID ascending for a stable tie-breaker.
- `GET /api/v1/changes/{id}?organizationId=org-one` returns a scoped change.
- `GET /api/v1/changes/{id}/timeline?organizationId=org-one` returns one merge
  fact with its original event ID, occurrence time and optional actor.

Missing authentication returns 401, an unbound organization returns 403, and
an ID outside the authorized organization returns 404. Invalid query values
return 400. There is no HTTP mutation endpoint for provider-owned merge facts.

## Verify

Build the three hardened integration images from the repository root first:

```sh
docker build --tag changuard-postgres:18.6-security.1 backend/docker/postgres
docker build --tag changuard-kafka:4.2.2-security.2 backend/docker/kafka
docker build --file backend/docker/schema-registry/Dockerfile --tag changuard-schema-registry:8.3.2-security.2 backend
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
