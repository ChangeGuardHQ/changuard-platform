# ChangeGuard backend

The backend currently contains one standalone Maven project: the
[Connector Service](services/connector-service/pom.xml). It targets Java 21 and
uses Spring Boot 4.0.8 with Spring MVC, Spring Security, Spring for Apache Kafka,
and Actuator. Its role is to receive external integration events and eventually
publish normalized ChangeGuard events.

The Maven configuration overrides Tomcat to 11.0.26 and the Jackson 3 BOM to
3.1.7 to address findings from the dependency scan while retaining the current
Spring Boot release. See
[Tomcat's security fixes](https://tomcat.apache.org/security-11.html) and the
[Jackson advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24).

The HTTP adapter, configuration, signature validation component, merged pull
request normalizer, canonical merged change event, and publisher are implemented.
Webhook processing is still pending,
so requests with the required headers and body currently receive
`503 Service Unavailable`. The service can start and expose health information
while that processing implementation is being built.

## Local development

Install a Java 21 JDK and make it available through `JAVA_HOME` or `PATH`. The
service includes a Maven wrapper configured for Maven 3.9.16; its first run
downloads Maven and any uncached dependencies.

From the repository root:

```sh
cd backend/services/connector-service
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` in the same directory. With Maven already installed,
the equivalent command from the repository root is:

```sh
mvn -f backend/services/connector-service/pom.xml spring-boot:run
```

The default base URL is <http://localhost:8081>. Check application health with:

```sh
curl -i http://localhost:8081/actuator/health
```

The current Maven startup and tests do not require a running Kafka broker or a
GitHub account. Health does not establish that webhook ingestion or Kafka
publishing is working.

## Docker development

The repository's [Compose configuration](../docker-compose.yaml) runs the
frontend, Connector Service, and a single-node Kafka broker:

```sh
docker compose up --build --wait
curl --fail http://localhost:8081/actuator/health
```

Run these commands from the repository root. To start only the connector and
its Kafka dependency, use `docker compose up --build --wait connector-service`.
The frontend is available on port 5173, the connector on 8081, and Kafka on
localhost:9092. Override host ports with `FRONTEND_PORT`, `CONNECTOR_PORT`, and
`KAFKA_PORT`. Containers use `kafka:9092` for broker traffic. The broker uses
plaintext listeners for local development and retains its data in the
`kafka_data` volume. Stop the stack with `docker compose down`.

Kafka uses the locally built `changuard-kafka:4.2.2-security.1` image, which
patches the upstream image's Jackson and libexpat vulnerabilities. All dependency
versions are pinned, and downloaded JARs are checked against their published
SHA-256 hashes. See the [Kafka image documentation](docker/kafka/README.md) for
the patches, JVM archive handling, and message smoke test. `KAFKA_IMAGE` can
override the local image tag.

Set `GITHUB_WEBHOOK_SECRET` in the root `.env` file or environment to override
the development placeholder. `CODE_EVENTS_TOPIC` can also be overridden.
Webhook processing remains pending, so structurally valid deliveries still
return 503 even when the containers are healthy.

The service's [Dockerfile](services/connector-service/Dockerfile) pins Temurin
21.0.12.1+1 and Alpine 3.24 for its build and runtime stages. It builds the
executable JAR using the Maven wrapper, then runs it as an
unprivileged user in a JRE image. Its health check calls `/actuator/health`.
Compose gives the connector a read-only filesystem and writable temporary
storage, and waits for Kafka health before starting it. Docker builds skip test
execution; the dedicated CI verification job runs the test suite.

## Build and test

Run these commands from `backend/services/connector-service`:

| Command | Purpose |
| --- | --- |
| `./mvnw test` | Run the backend tests. |
| `./mvnw -Dtest=GitHubWebhookControllerTests test` | Run the webhook controller tests. |
| `./mvnw verify` | Run the Maven lifecycle through verification, including tests and packaging. |
| `java -jar target/connector-service-0.0.1-SNAPSHOT.jar` | Start the packaged service after a successful build. |

Tests cover application startup, the unavailable-processing response, webhook
header validation, exact request-byte preservation, service error propagation,
security access rules, Kafka property overrides, JSON serialization, repository
keys, deferred publication acknowledgements, publication failures, merged pull
request mapping, malformed payload rejection, stable event identities, and the
canonical event JSON contract. Signature tests cover GitHub's published test
vector, raw byte hashing, Unicode, formatting changes, malformed signatures,
and invalid secret configuration. The
controller tests supply a recording service implementation. The Kafka tests
validate configuration and serialization, and exercise sends through Kafka's
mock producer without a running broker.

Reports are written to `services/connector-service/target/surefire-reports/`
relative to this directory. There is currently no backend parent `pom.xml`.
The [backend CI workflow](../.github/workflows/backend_ci.yml) targets
`services/connector-service` directly and uses Temurin 21.0.12.1+1 with the Maven
wrapper on Ubuntu 24.04. The `setup-java` input uses Adoptium's equivalent SemVer
identifier, `21.0.12+101.0.LTS`, to select that exact release.
Independent jobs run Maven verification, CodeQL Java analysis with the extended
security queries, Trivy dependency/secret/configuration scans, and the container
build, Compose startup check, Kafka message smoke test, and connector/Kafka image
scans. The image scans cover packaged Java dependencies and operating-system
packages. Trivy fails on HIGH or
CRITICAL findings, including unfixed vulnerabilities. Test and security reports
are retained as workflow artifacts; CodeQL publishes findings to GitHub code
scanning.

Local validation on October 4, 2026 found no HIGH/CRITICAL findings in the
connector, frontend, or patched Kafka images. The upstream `apache/kafka:4.2.2`
still has five HIGH findings; the [Kafka Dockerfile](docker/kafka/Dockerfile)
fixes them with libexpat 2.8.5-r0 and Jackson 2.21.7. CI builds and scans that
patched image and keeps the same HIGH/CRITICAL failure policy.

Backend changes, workflow changes, and Compose changes trigger CI. A weekly
schedule also reruns checks as vulnerability databases change. The workflows
pin external actions to verified commits and keep repository permissions at
read-only except the CodeQL job's code-scanning upload permission. These
CodeQL and Trivy configurations follow their
[CodeQL action inputs](https://github.com/github/codeql-action/blob/main/init/action.yml)
and [Trivy action documentation](https://github.com/aquasecurity/trivy-action).
Runtime and CI tool versions are explicitly pinned; see the
[shared CI version table](../README.md#25-cicd).

## Configuration

Defaults live in
[application.yaml](services/connector-service/src/main/resources/application.yaml).

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker addresses used by the producer factory. |
| `CODE_EVENTS_TOPIC` | `changeguard.code-events` | Destination topic used by `CodeEventProducer`. |
| `GITHUB_WEBHOOK_SECRET` | `change-me` | Shared secret used by the signature validation component. |
| `SPRING_SECURITY_USER_NAME` | `user` | Spring Boot's development HTTP Basic username. |
| `SPRING_SECURITY_USER_PASSWORD` | Generated at startup | Spring Boot's development HTTP Basic password. |

The publisher reads the topic setting and the signature validator reads the
secret setting; `change-me` is a placeholder. Their integration into webhook
processing is pending, so the HTTP endpoint does not yet invoke either component.

The producer factory builds its settings from `spring.kafka.*`, including
external overrides for broker security and producer properties. Current
defaults are:

- String keys and JSON values using `JacksonJsonSerializer`.
- `acks: all` and `enable.idempotence: true`.
- `spring.json.add.type.headers: false`, so serialized values carry no Java
  class-name headers.

Topic creation, webhook delivery deduplication, and publication from webhook
processing are pending. The platform's [event design](../README.md#10-kafka-design) plans
versioned schemas and Schema Registry integration beyond the current JSON
serializer configuration.

## GitHub event normalization

[GitHubEventNormalizer](services/connector-service/src/main/java/com/changeguard/connector/normalization/GitHubEventNormalizer.java)
is a Spring component with this API:

```java
Optional<GitHubMergedPullRequest> normalize(String eventType, String deliveryId, byte[] payload);
```

The caller must verify the signature before invoking the normalizer. It parses
verified request bytes and produces a merged pull request DTO only for
`pull_request` events with `action: closed` and `merged: true`. Other event
types, other actions, and closed but unmerged pull requests return an empty
result. Opened/reopened pull request and push event models remain planned work.

| Output field | Source |
| --- | --- |
| `deliveryId` | The supplied delivery header, retained unchanged. |
| `repositoryId` | Positive `repository.id`, converted to a String without losing integer precision. |
| `repositoryFullName` | `repository.full_name`. |
| `pullRequestNumber` | Positive `pull_request.number`. |
| `pullRequestTitle` | `pull_request.title`; remains null if absent. |
| `commitSha` | `pull_request.merge_commit_sha`, retained in full. |
| `sourceBranch` | `pull_request.head.ref`. |
| `targetBranch` | `pull_request.base.ref`. |
| `actorLogin` | `pull_request.merged_by.login`, then `sender.login` as a fallback; null if neither is available. |
| `mergedAt` | `pull_request.merged_at`, parsed as an `Instant` without substituting receipt time. |

The merge commit field identifies the commit that reached the target branch,
including squash and rebase merges, according to
[GitHub's pull request documentation](https://docs.github.com/en/rest/pulls/pulls#get-a-pull-request).
The normalizer uses that field and does not infer the merge actor from the pull
request author.

Malformed pull request JSON, duplicate fields, trailing JSON, missing merge
state, invalid/missing merge timestamps, and missing required correlation fields raise
`InvalidGitHubPayloadException`. Numeric identities and Boolean merge state are
read without scalar coercion or fractional-number truncation. Unknown GitHub
fields are ignored by the webhook DTO. Reader configuration is local to the
normalizer and preserves the shared application's mapper configuration.

The normalizer retains delivery identity and maps repeated inputs consistently;
durable deduplication belongs in the future webhook processing implementation.
Its sanitized fixture and unit tests are in
[GitHubEventNormalizerTests.java](services/connector-service/src/test/java/com/changeguard/connector/normalization/GitHubEventNormalizerTests.java)
and [pull-request-merged.json](services/connector-service/src/test/resources/github/pull-request-merged.json).

## Canonical merged change event

[CodeChangeMergedEvent](services/connector-service/src/main/java/com/changeguard/connector/event/CodeChangeMergedEvent.java)
is an immutable Java record following the platform's
[event envelope](../README.md#11-canonical-event-model). Construct it from the
normalizer's DTO and trusted integration context:

```java
CodeChangeMergedEvent event = CodeChangeMergedEvent.fromGitHub(
        mergedPullRequest, organizationId, integrationId, receivedAt);
```

`organizationId` and `integrationId` are internal ChangeGuard identifiers. The
caller supplies the timestamp recorded when the delivery arrived. The factory
uses the DTO's merge timestamp for `occurredAt`, matching the date-time field in
[Octokit's GitHub webhook schema](https://github.com/octokit/webhooks/blob/main/payload-schemas/api.github.com/common/pull-request.schema.json).

| Envelope field | Value |
| --- | --- |
| `eventId` | Deterministic name-based UUID scoped to the organization, integration, and GitHub delivery. |
| `eventType` / `eventVersion` | Fixed `CodeChangeMerged` / `1`. |
| `occurredAt` / `receivedAt` | Provider merge time / connector receipt time, serialized as ISO-8601 timestamps. |
| `organizationId` | Owning ChangeGuard organization from trusted context. |
| `source` | `provider: github` and the internal integration ID. |
| `actor` | GitHub actor login as `id`, with `type: UNKNOWN`; null if unavailable. |
| `correlation` | Null in the GitHub factory; the record constructor accepts existing `traceId` and `changeId` values. |
| `payload` | Delivery ID, repository ID/full name, pull request number/title, full merge commit SHA, and source/target branches. |

Required identity, timing, source, and merge fields are validated when the record
is constructed. Optional titles and actor data remain absent when unavailable;
the factory does not infer whether an actor is human, a bot, or AI. The event ID
stays the same on redelivery even if `receivedAt` changes. Consumers still need
durable deduplication. Identity components use length prefixes before hashing
so embedded separators do not create ambiguous identities.

## Code event publishing

[CodeEventProducer](services/connector-service/src/main/java/com/changeguard/connector/messaging/CodeEventProducer.java)
is a Spring component with this API:

```java
CompletableFuture<SendResult<String, Object>> publish(CodeChangeMergedEvent event);
```

It sends the canonical event envelope to the configured `CODE_EVENTS_TOPIC`,
using `event.payload().repositoryId()` as the Kafka key. The JSON serializer
comes from the shared Kafka template. Null events are rejected before sending;
required repository IDs are validated during event construction. A blank topic
fails component initialization.

The returned future completes on Kafka acknowledgement or completes
exceptionally on a later send failure. Errors raised before Kafka returns a
future propagate directly. Callers must observe completion before treating an
event as published. The publisher does not flush each send or add application
retries. Kafka failure logs omit record keys and values.

Live publication requires a broker. The JSON now carries a version-one envelope
instead of the raw GitHub DTO. Avro schemas and Schema Registry integration
remain planned work. The webhook processing implementation must connect
normalization, trusted integration lookup, event construction, and publication,
then handle acknowledgement failures.

## GitHub webhook API

```text
POST /api/v1/integrations/github/webhook
Content-Type: application/json
```

The endpoint accepts the request body as `byte[]` and passes those bytes to the
processing service unchanged. JSON parsing belongs in the processing service,
after signature verification.

| Header | Controller requirement |
| --- | --- |
| `X-GitHub-Event` | Present and nonblank; event routing belongs to the processing service. |
| `X-GitHub-Delivery` | Present and nonblank; forwarded unchanged for future receipt tracking. |
| `X-Hub-Signature-256` | `sha256=` followed by exactly 64 hexadecimal characters. |

The controller checks signature syntax. Cryptographic HMAC verification is
pending in the service implementation.

`GitHubSignatureValidator.isValid(byte[] payload, String signatureHeader)` hashes
the unmodified body directly and compares the decoded SHA-256 digests with
`MessageDigest.isEqual`. It rejects malformed signature headers and fails
initialization when the configured secret is blank. Invoke it before decoding
or parsing the request body, following
[GitHub's signature validation guidance](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries).

| Status | Meaning |
| --- | --- |
| `202 Accepted` | A processing service exists and `handleWebhook` returned normally; the response body is empty. |
| `400 Bad Request` | Required event/delivery metadata is missing or blank, or the body is missing. |
| `401 Unauthorized` | The signature header is missing or malformed. |
| `415 Unsupported Media Type` | The request content type is unsupported. |
| `503 Service Unavailable` | No webhook processing service is registered; this is the current response for requests that pass controller validation. |

Service rejections expressed as `ResponseStatusException` retain their HTTP
status. The controller acknowledges only a normal return from the service.

To exercise the current endpoint locally with a synthetic signature:

```sh
curl -i http://localhost:8081/api/v1/integrations/github/webhook \
  -H 'Content-Type: application/json' \
  -H 'X-GitHub-Event: push' \
  -H 'X-GitHub-Delivery: 72d3162e-cc78-11e3-81ab-4c9367dc0958' \
  -H 'X-Hub-Signature-256: sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  --data-binary '{}'
```

Expect `503`. This fixture exercises header validation and unavailable-service
handling; its signature has not been computed from a webhook secret.

## Security and observability

[SecurityConfig](services/connector-service/src/main/java/com/changeguard/connector/config/SecurityConfig.java)
applies these access rules:

- The webhook POST is public and exempt from CSRF checks. Its processing service
  must authenticate deliveries through signature verification.
- GET requests to `/actuator/health` and `/actuator/health/**` are public.
  Health groups such as readiness and liveness depend on Actuator configuration.
- Other routes require HTTP Basic authentication. Writes outside the webhook
  keep CSRF protection. Form login, logout, and the request cache are disabled;
  authentication does not create a session.
- Internal error dispatches are permitted so application errors keep their
  intended HTTP status.

The development username defaults to `user`; Spring Boot prints a generated
password at startup when no password is configured. For example, this command
prompts for that password and requests the authenticated info endpoint:

```sh
curl --user user http://localhost:8081/actuator/info
```

OAuth2/JWT authentication is planned. Actuator exposes health and info with the
current dependencies. Prometheus exposure/export is configured in YAML, but the
Prometheus registry dependency is absent, so `/actuator/prometheus` is currently
unavailable. Health details use `show-details: when_authorized`.

Web logging defaults to INFO to suppress Spring's DEBUG request-body output.
Connector package logging is DEBUG; the controller does not log payloads or
signatures.

## Source map and remaining work

Paths below are relative to
`services/connector-service/src/main/java/com/changeguard/connector/`:

| File | Current responsibility or status |
| --- | --- |
| [ConnectorServiceApplication.java](services/connector-service/src/main/java/com/changeguard/connector/ConnectorServiceApplication.java) | Spring Boot entry point and component scanning. |
| [config/KafkaProducerConfig.java](services/connector-service/src/main/java/com/changeguard/connector/config/KafkaProducerConfig.java) | `ProducerFactory<String, Object>` and `KafkaTemplate<String, Object>` beans using configured Kafka properties. |
| [config/SecurityConfig.java](services/connector-service/src/main/java/com/changeguard/connector/config/SecurityConfig.java) | Stateless HTTP security and route access rules. |
| [api/GitHubWebhookController.java](services/connector-service/src/main/java/com/changeguard/connector/api/GitHubWebhookController.java) | HTTP/header validation, raw-byte handoff, and acknowledgement after service return. |
| [github/GitHubWebhookService.java](services/connector-service/src/main/java/com/changeguard/connector/github/GitHubWebhookService.java) | Processing interface; no application implementation is registered. |
| [github/GitHubSignatureValidator.java](services/connector-service/src/main/java/com/changeguard/connector/github/GitHubSignatureValidator.java) | HMAC-SHA256 verification of unmodified request bytes; webhook processing integration is pending. |
| [normalization/GitHubEventNormalizer.java](services/connector-service/src/main/java/com/changeguard/connector/normalization/GitHubEventNormalizer.java) | Maps verified closed-and-merged pull request payloads into a provider DTO with merge time; rejects malformed payloads and skips unsupported activity. |
| [event/CodeChangeMergedEvent.java](services/connector-service/src/main/java/com/changeguard/connector/event/CodeChangeMergedEvent.java) | Validated version-one event envelope with stable delivery identity, trusted organization/integration context, and merge details. |
| [messaging/CodeEventProducer.java](services/connector-service/src/main/java/com/changeguard/connector/messaging/CodeEventProducer.java) | Publishes canonical merged change events using repository keys and returns acknowledgement futures. |

The processing contract is:

```java
void handleWebhook(String eventType, String deliveryId, String signature, byte[] payload);
```

A registered implementation must verify the SHA-256 HMAC before parsing or
trusting payload identifiers, handle supported and unsupported events
explicitly, and durably receive supported deliveries before returning. Verified
unsupported events may be ignored. Rejections and processing failures must
propagate to the controller.

Next work includes the webhook processing implementation, durable receipt
tracking and deduplication, additional event models, and publication integration. The
[GitHub integration delivery plan](../docs/plans/github-repository-integration.md)
covers installation, persistence, transactional outbox, canonical schemas, and
the first complete ingestion-to-timeline flow. The
[platform README](../README.md) describes the broader backend architecture and
planned services.
