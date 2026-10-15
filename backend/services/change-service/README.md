# Change Service

Java 21 and Spring Boot 4 infrastructure for the ChangeGuard change lifecycle
service. The service starts with PostgreSQL, JPA, Kafka transport, HTTP Basic
authentication, Actuator health probes, and Prometheus metrics. Business
endpoints, event contracts, entities, idempotency operations, and event handlers
remain scaffolds.

## Run with Docker Compose

From the repository root:

```sh
docker compose up --build --wait change-service
curl --fail http://localhost:8082/actuator/health/readiness
curl --fail http://localhost:8082/actuator/health/liveness
```

Compose also starts the service's PostgreSQL database and Kafka broker, waits
for their health checks, and retains database data in `change_postgres_data`.
The application runs as a non-root user with a read-only root filesystem and
writable temporary storage. Stop the stack with `docker compose down`.

Compose sets the development HTTP Basic credentials to `changeguard` /
`change-me`. Override them with `CHANGE_SERVICE_USER` and
`CHANGE_SERVICE_PASSWORD`. Database credentials use `CHANGE_DB_USERNAME` and
`CHANGE_DB_PASSWORD`; their development defaults are both `changeguard`.

## Run the application locally

Start the dependencies from the repository root:

```sh
docker compose up --build --wait postgres kafka
```

Then, from `backend/services/change-service`:

```sh
./mvnw spring-boot:run
```

Use `mvnw.cmd` on Windows. A standalone Maven launch uses Spring Boot's default
`user` account and a password printed at startup, unless
`SPRING_SECURITY_USER_NAME` and `SPRING_SECURITY_USER_PASSWORD` are supplied.

## Configuration

Defaults are in [application.yml](src/main/resources/application.yml).

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | Application HTTP port. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/changeguard_change` | JDBC connection URL. |
| `DB_USERNAME` | `changeguard` | Database login. |
| `DB_PASSWORD` | `changeguard` | Database password. |
| `DB_POOL_SIZE` | `10` | Maximum JDBC pool size. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker addresses. |
| `KAFKA_CONSUMER_GROUP` | `change-service-group` | Consumer group. |
| `CODE_EVENTS_TOPIC` | `changeguard.code-events` | Topic for future business listeners. |
| `KAFKA_LISTENER_AUTO_STARTUP` | `false` | Enable only after business listeners are implemented. |
| `FLYWAY_ENABLED` | `false` | Enable after the domain migration files contain real DDL. |

Compose host ports can be changed with `CHANGE_SERVICE_PORT`, `CHANGE_DB_PORT`,
and `KAFKA_PORT`. When running Maven directly, update the JDBC URL or broker
addresses to match any changed dependency ports. Compose uses internal
container addresses independently of host ports.

## Infrastructure behavior

- Health, liveness, and readiness GET endpoints are public. Readiness includes
  the PostgreSQL connection; liveness checks the application's lifecycle.
  Anonymous health responses omit component details.
- Other HTTP paths, including `/actuator/info` and `/actuator/prometheus`,
  require HTTP Basic authentication. Requests do not create HTTP sessions.
  CSRF protection remains enabled for browser-supplied Basic credentials.
- Kafka consumers use record acknowledgements and disable automatic offset
  commits. Jackson 3 deserialization preserves the complete event as a JSON
  tree and ignores Java type headers supplied by producers. Deserialization
  failures are passed to the shared error handler.
- The error handler stops a listener on failure so its failed record can be
  replayed after restart. Retry and dead-letter policies belong to the future
  business consumer. No business listener is registered, so the application
  currently consumes no events. Readiness does not test broker connectivity.
- Hibernate validates mappings and does not generate tables. Flyway is wired
  but disabled by default because the two domain migrations are still
  placeholders. Enabling them as-is would record empty migrations and reserve
  their versions. Replace them with real schema definitions before enabling
  Flyway.

The Kafka configuration uses Spring Kafka's
[Jackson JSON support](https://docs.spring.io/spring-kafka/reference/kafka/serdes.html)
and [container-stopping error handler](https://docs.spring.io/spring-kafka/docs/4.0.x/api/org/springframework/kafka/listener/CommonContainerStoppingErrorHandler.html).

## Verify

From this directory:

```sh
./mvnw verify
```

Tests require a running Docker daemon. They start a disposable PostgreSQL
container and an embedded Kafka broker; external database and broker instances
are unnecessary. The first run downloads Maven dependencies and the PostgreSQL
image. PostgreSQL startup tests use a test-only Flyway migration to verify JDBC,
migrations, and JPA without reserving the domain migration versions.

Infrastructure tests cover authentication, CSRF protection, stateless requests,
health probes, Prometheus output, broker/security property overrides, JSON
transport, malformed messages, and stopping listeners without committing failed
records. The root backend CI workflow verifies both backend services and builds
and smoke-tests their container images.
