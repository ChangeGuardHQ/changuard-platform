# changuard-platform

**ChangeGuard** is an event-driven software change intelligence platform that connects source control, CI/CD, deployments, observability, and AI-assisted engineering activity to answer three critical questions:

1. **What changed?**
2. **Did that change hurt production?**
3. **Who or what made the change?**

ChangeGuard is designed around a durable software-delivery event model rather than a collection of disconnected dashboards.

Its long-term direction is to become a vendor-neutral software delivery intelligence and governance layer that helps engineering teams understand, correlate, audit, and eventually automate decisions across the software lifecycle.

---

# Table of Contents

- [1. Product Vision](#1-product-vision)
- [2. Core Problem](#2-core-problem)
- [3. Product Principles](#3-product-principles)
- [4. V1 Scope](#4-v1-scope)
- [5. V2 Scope](#5-v2-scope)
- [6. Technology Stack](#6-technology-stack)
- [7. High-Level Architecture](#7-high-level-architecture)
- [8. Domain Model](#8-domain-model)
- [9. Event-Driven Architecture](#9-event-driven-architecture)
- [10. Kafka Design](#10-kafka-design)
- [11. Canonical Event Model](#11-canonical-event-model)
- [12. Microservice Boundaries](#12-microservice-boundaries)
- [13. Database Strategy](#13-database-strategy)
- [14. Redis Strategy](#14-redis-strategy)
- [15. Transactional Outbox](#15-transactional-outbox)
- [16. Idempotency and Delivery Semantics](#16-idempotency-and-delivery-semantics)
- [17. Correlation Engine](#17-correlation-engine)
- [18. AI Audit Model](#18-ai-audit-model)
- [19. API Design](#19-api-design)
- [20. Security](#20-security)
- [21. Observability](#21-observability)
- [22. Reliability and Resilience](#22-reliability-and-resilience)
- [23. Local Development](#23-local-development)
- [24. Repository Structure](#24-repository-structure)
- [25. CI/CD](#25-cicd)
- [26. Deployment Strategy](#26-deployment-strategy)
- [27. Testing Strategy](#27-testing-strategy)
- [28. V1 Delivery Plan](#28-v1-delivery-plan)
- [29. V2 Delivery Plan](#29-v2-delivery-plan)
- [30. Future Evolution](#30-future-evolution)
- [31. Non-Goals](#31-non-goals)
- [32. Engineering Standards](#32-engineering-standards)

---

# 1. Product Vision

Modern software delivery is fragmented.

A single production change may involve:

```text
GitHub
  ↓
Pull Request
  ↓
CI Build
  ↓
Artifact
  ↓
Deployment
  ↓
Production Service
  ↓
Prometheus / Grafana
  ↓
Incident
```

Each system understands only part of the lifecycle.

ChangeGuard connects these events into a coherent software-change timeline.

The platform does not initially replace:

- GitHub
- GitLab
- Bitbucket
- Jenkins
- GitHub Actions
- Argo CD
- Spinnaker
- Kubernetes
- Prometheus
- Grafana
- Datadog
- Jira
- ServiceNow

Instead, ChangeGuard sits above these systems and provides:

- normalized delivery events
- cross-system relationships
- production-impact correlation
- software-change intelligence
- AI-agent auditability
- evidence-based investigation
- eventually, policy-controlled automation

---

# 2. Core Problem

Engineering teams often investigate production problems manually.

A typical incident investigation may require checking:

```text
Git repository
    ↓
Recent pull requests
    ↓
CI pipeline
    ↓
Deployment history
    ↓
Service ownership
    ↓
Monitoring dashboards
    ↓
Production alerts
```

This process is slow and error-prone.

The challenge becomes even greater as AI agents begin participating in:

- coding
- testing
- pull requests
- infrastructure changes
- deployment workflows
- incident investigation
- remediation

Organizations increasingly need to understand not only:

> What changed?

but also:

> Was the change AI-assisted?

> Which model or agent performed the action?

> Who approved it?

> What happened in production afterward?

ChangeGuard is designed to make this traceability native to the platform.

---

# 3. Product Principles

## 3.1 Integrate, do not replace

Existing engineering systems remain the systems of record.

ChangeGuard connects them.

## 3.2 Events are first-class data

Every important lifecycle activity should be represented as a canonical event.

Examples:

```text
CodeChangeMerged
BuildCompleted
DeploymentCompleted
ServiceHealthDegraded
AIActionExecuted
ChangeImpactDetected
```

## 3.3 Historical replay matters

The platform must be able to:

- replay events
- rebuild projections
- recalculate correlations
- add new consumers
- reprocess historical activity
- audit previous decisions

This is a major reason Apache Kafka is the core event backbone.

## 3.4 Vendor neutrality

Internal services should operate on ChangeGuard's canonical model, not provider-specific payloads.

The platform should reason about:

```text
DeploymentCompleted
```

rather than:

```text
jenkins-specific-deployment-payload
```

## 3.5 AI must be explainable and auditable

AI-generated insights should include evidence.

AI actions should include:

- agent identity
- model
- tools used
- permissions used
- approval state
- affected resources
- outcome
- cost where applicable

## 3.6 Automation starts read-only

V1 focuses on:

- ingestion
- correlation
- analysis
- visibility

Write actions against production systems are intentionally deferred.

---

# 4. V1 Scope

V1 solves three problems only.

## 4.1 Track what changed

ChangeGuard creates a normalized change timeline connecting:

```text
Pull Request
    ↓
Commit
    ↓
Build
    ↓
Deployment
    ↓
Service
```

Initial integrations:

- GitHub
- Jenkins or GitHub Actions
- Prometheus

## 4.2 Determine whether the change hurt production

After a deployment, ChangeGuard observes related production signals.

Example:

```text
checkout-service deployed at 14:14
           ↓
error rate increases at 14:18
           ↓
latency increases
           ↓
ChangeGuard correlates signals
           ↓
possible regression detected
```

The system should produce evidence, not just an opaque score.

Example:

```text
Deployment: deploy-4821
Service: checkout-service
Deployment time: 14:14

Observed:
- error rate 0.4% → 6.8%
- p95 latency 220ms → 890ms
- restart count 0 → 14

Correlation:
- degradation began 4 minutes after deployment
- no similar degradation in previous baseline window

Result:
LIKELY_NEGATIVE_IMPACT
```

## 4.3 Record whether a human or AI agent made the change

Each software change may record:

```text
actorType
humanUser
aiAgent
modelProvider
modelName
approvalStatus
approvalUser
toolsUsed
permissionsUsed
```

V1 does not require full AI governance.

It establishes the audit foundation.

---

# 5. V2 Scope

V2 extends ChangeGuard from basic change intelligence into release and incident intelligence.

## 5.1 Release safety intelligence

Before promotion, ChangeGuard can aggregate evidence such as:

- build status
- test status
- deployment history
- prior failure rate
- recent incidents
- service health
- change size
- AI involvement

The platform should not make an irreversible autonomous deployment decision.

Instead it produces an evidence-backed release assessment.

## 5.2 Incident-to-change correlation

V2 should correlate:

```text
Incident
   ↓
Affected Service
   ↓
Recent Deployment
   ↓
Build
   ↓
Commit
   ↓
Pull Request
```

Example question:

> What changed before this incident?

## 5.3 AI-assisted investigation

V2 may introduce a dedicated AI service for:

- deployment investigation
- incident summaries
- suspected root-cause explanation
- timeline generation
- recommended next steps
- evidence summarization

Recommended AI stack:

```text
Python
+
FastAPI
```

The core platform remains:

```text
Java
+
Spring Boot
```

## 5.4 Initial controlled actions

V2 may support low-risk, approval-based actions such as:

- create investigation ticket
- notify service owner
- open incident
- request rollback approval
- trigger diagnostic workflow

Production mutations should remain policy-controlled.

---

# 6. Technology Stack

## Frontend

```text
React
TypeScript
Material UI
React Router
TanStack Query
Axios or Fetch
```

## Core Backend

```text
Java 21+
Spring Boot
Spring Web
Spring Security
Spring Data JPA
Spring for Apache Kafka
Spring Validation
Spring Actuator
Micrometer
OpenTelemetry
```

## AI Services

V2:

```text
Python
FastAPI
Pydantic
LLM provider SDKs
```

Potential later tools:

```text
LangGraph
LlamaIndex
scikit-learn
PyTorch
```

only where justified.

## Event Platform

```text
Apache Kafka
Kafka Streams
Schema Registry
Avro or Protobuf
```

Preferred initial direction:

```text
Avro + Schema Registry
```

for lifecycle events.

## Data

```text
PostgreSQL
Redis
```

## Infrastructure

```text
Docker
Docker Compose
Kubernetes
Terraform
AWS
```

## Observability

```text
Prometheus
Grafana
OpenTelemetry
Loki
```

---

# 7. High-Level Architecture

```text
                           ┌─────────────────────┐
                           │  React + TS + MUI   │
                           └──────────┬──────────┘
                                      │
                                      ▼
                           ┌─────────────────────┐
                           │ API Gateway / BFF   │
                           └──────────┬──────────┘
                                      │
                 ┌────────────────────┼────────────────────┐
                 │                    │                    │
                 ▼                    ▼                    ▼
        ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
        │ Change Service  │  │ Audit Service   │  │ Query Service   │
        └────────┬────────┘  └────────┬────────┘  └────────┬────────┘
                 │                    │                    │
                 └────────────────────┼────────────────────┘
                                      │
                                      ▼
                          ┌────────────────────────┐
                          │      Apache Kafka      │
                          │ Canonical Event Stream │
                          └────────────┬───────────┘
                                       │
                   ┌───────────────────┼───────────────────┐
                   │                   │                   │
                   ▼                   ▼                   ▼
          ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
          │ Connector Svc  │  │ Correlation Svc│  │ AI Service V2  │
          └───────┬────────┘  └───────┬────────┘  └────────────────┘
                  │                   │
       ┌──────────┼──────────┐        │
       ▼          ▼          ▼        │
    GitHub     Jenkins    Prometheus   │
                                      │
                             ┌────────┴─────────┐
                             ▼                  ▼
                       PostgreSQL            Redis
```

---

# 8. Domain Model

The V1 domain should remain intentionally small.

## Core entities

```text
Organization
Service
Repository
PullRequest
Commit
Build
Deployment
ChangeRecord
ServiceHealthSignal
ChangeImpact
Actor
AIActor
AuditEvent
```

## Core relationship model

```text
Organization
    │
    └── Service
          │
          ├── Repository
          │      │
          │      └── PullRequest
          │             │
          │             └── Commit
          │
          ├── Build
          │
          ├── Deployment
          │
          └── HealthSignal
```

A `ChangeRecord` connects the important lifecycle artifacts.

Example:

```text
ChangeRecord
──────────────────────────
id
organizationId
serviceId
repositoryId
pullRequestId
commitId
buildId
deploymentId
actorType
deployedAt
environment
status
```

---

# 9. Event-Driven Architecture

ChangeGuard is event-driven because software delivery itself is event-driven.

Examples:

```text
PR merged
Build completed
Deployment completed
Health degraded
Alert fired
AI action executed
Incident created
```

Internal services should communicate through events where asynchronous coupling is appropriate.

Synchronous REST should primarily be used for:

- user-facing queries
- administrative commands
- configuration
- authentication
- integration setup
- request/response interactions

Kafka should be used for:

- lifecycle events
- domain events
- derived events
- audit streams
- correlation processing
- asynchronous workflows

---

# 10. Kafka Design

Kafka is the durable event backbone.

Its purpose is not merely asynchronous messaging.

It provides:

- retention
- replay
- independent consumer groups
- ordered processing within partitions
- scalable consumers
- stream processing
- decoupled services
- historical reprocessing

## 10.1 Initial topics

Recommended V1 topics:

```text
changeguard.code-events
changeguard.build-events
changeguard.deployment-events
changeguard.runtime-events
changeguard.ai-events
changeguard.correlation-events
changeguard.audit-events
```

Avoid creating a topic per event type.

Use typed events within bounded topic families.

## 10.2 Event keys

Use stable keys.

Examples:

```text
serviceId
repositoryId
deploymentId
```

For most lifecycle events, prefer:

```text
key = serviceId
```

This helps preserve ordering for a service timeline.

## 10.3 Partitioning

Example:

```text
changeguard.deployment-events

Partition 0 → checkout-service
Partition 1 → inventory-service
Partition 2 → user-service
```

Consumer groups allow horizontal scaling.

## 10.4 Consumer groups

Examples:

```text
change-service-group
correlation-service-group
audit-service-group
analytics-service-group
```

Each consumer group independently processes the same event stream.

## 10.5 Retention

Retention should be environment-specific.

Example approach:

```text
development:
short retention

staging:
medium retention

production:
long retention
```

Audit and lifecycle history may require significantly longer retention than transient operational topics.

## 10.6 Schema Registry

Events should not be unmanaged JSON.

Use a schema registry.

Preferred direction:

```text
Apache Avro
+
Schema Registry
```

Goals:

- backward compatibility
- controlled evolution
- event versioning
- producer validation
- consumer safety

---

# 11. Canonical Event Model

All internal events should share a standard envelope.

Example:

```json
{
  "eventId": "01J8X...",
  "eventType": "DeploymentCompleted",
  "eventVersion": 1,
  "occurredAt": "2026-10-01T19:14:00Z",
  "receivedAt": "2026-10-01T19:14:02Z",
  "organizationId": "org-123",
  "source": {
    "provider": "jenkins",
    "integrationId": "integration-55"
  },
  "actor": {
    "type": "HUMAN",
    "id": "user-88"
  },
  "correlation": {
    "traceId": "trace-abc",
    "changeId": "change-4821"
  },
  "payload": {}
}
```

## Example event types

### Code

```text
PullRequestOpened
PullRequestMerged
CommitCreated
```

### Build

```text
BuildStarted
BuildCompleted
BuildFailed
ArtifactCreated
```

### Deployment

```text
DeploymentStarted
DeploymentCompleted
DeploymentFailed
RollbackCompleted
```

### Runtime

```text
ServiceHealthDegraded
ServiceHealthRecovered
MetricThresholdExceeded
```

### AI

```text
AIActionExecuted
AIChangeProposed
AIChangeApproved
```

### Correlation

```text
ChangeImpactDetected
ChangeImpactCleared
```

---

# 12. Microservice Boundaries

V1 should use a small number of meaningful services.

Do not create dozens of tiny services.

## 12.1 Connector Service

Responsibilities:

- receive webhooks
- call external APIs when needed
- verify webhook authenticity
- normalize provider payloads
- deduplicate inbound events
- publish canonical events

Initial connectors:

```text
GitHub
Jenkins / GitHub Actions
Prometheus
```

Future:

```text
GitLab
Bitbucket
Argo CD
Spinnaker
Kubernetes
Datadog
PagerDuty
Jira
ServiceNow
```

## 12.2 Change Service

Responsibilities:

- build change records
- relate PRs, commits, builds, and deployments
- store current lifecycle state
- expose change APIs
- publish domain events

Owns:

```text
repository
pull request
commit
build
deployment
change record
```

## 12.3 Correlation Service

Responsibilities:

- consume deployment events
- consume runtime events
- maintain correlation windows
- compare pre/post-deployment health
- calculate change impact
- publish impact events

Potential implementation:

```text
Spring Boot
+
Kafka Streams
```

## 12.4 Audit Service

Responsibilities:

- append audit records
- track actor identity
- track AI participation
- store approval metadata
- expose audit history

Audit records should be immutable from normal application workflows.

## 12.5 Query/BFF Service

Responsibilities:

- frontend-oriented APIs
- aggregate read models
- reduce frontend orchestration
- expose dashboard queries

V1 may combine this responsibility with the API gateway if simplicity is preferred.

## 12.6 AI Service — V2

Responsibilities:

- summarize investigations
- generate evidence-based explanations
- correlate contextual information
- produce recommended actions
- interact with external LLMs

The AI service should not directly modify production systems.

All actions must pass through platform policy and approval boundaries.

---

# 13. Database Strategy

PostgreSQL is the primary system of record.

V1 does not require a graph database.

Relationships should initially be modeled relationally.

## Recommended database ownership

Each service should conceptually own its data.

Avoid direct database access across services.

Example:

```text
Change Service
    ↓
change database/schema

Audit Service
    ↓
audit database/schema

Correlation Service
    ↓
correlation database/schema
```

For early development, these may run in the same PostgreSQL cluster while maintaining ownership boundaries.

## Example tables

```text
organizations
services
repositories
pull_requests
commits
builds
deployments
change_records
health_signals
change_impacts
actors
ai_actors
audit_events
outbox_events
processed_events
```

---

# 14. Redis Strategy

Redis is not the primary database.

Use Redis for:

- cache
- rate limiting
- short-lived state
- idempotency keys
- deduplication
- distributed locks
- recent-deployment lookup
- correlation windows
- temporary workflow state

Example:

```text
key:
recent-deployment:checkout-service

value:
deploy-4821

TTL:
30 minutes
```

PostgreSQL remains the source of durable business truth.

---

# 15. Transactional Outbox

Never depend on:

```text
write database
then
publish Kafka event
```

as two independent operations.

Failure example:

```text
database commit succeeded
Kafka publish failed
```

The system becomes inconsistent.

Use a transactional outbox.

```text
PostgreSQL Transaction
       │
       ├── INSERT deployment
       │
       └── INSERT outbox_event
                 │
                 ▼
          Outbox Publisher
                 │
                 ▼
               Kafka
```

Later, CDC may be introduced:

```text
PostgreSQL
    ↓
Debezium
    ↓
Kafka
```

---

# 16. Idempotency and Delivery Semantics

Consumers must assume duplicate delivery is possible.

Each event has:

```text
eventId
```

Consumers record processed IDs.

Example:

```text
processed_events
────────────────────────
consumer
event_id
processed_at
```

Before processing:

```text
already processed?
    │
YES ─────→ ignore
    │
NO
    ↓
process
```

This protects against duplicate:

- deployments
- correlations
- audits
- notifications

---

# 17. Correlation Engine

The correlation engine is central to ChangeGuard.

V1 correlation can initially be rules-based.

Example:

```text
DeploymentCompleted
      +
HealthDegradation
within 15 minutes
      +
same service
      =
possible negative impact
```

## Baseline comparison

A deployment should be evaluated against a baseline.

Example:

```text
before deployment:
error rate = 0.4%
p95 latency = 220ms

after deployment:
error rate = 6.8%
p95 latency = 890ms
```

## Initial signals

Potential V1 metrics:

- HTTP 5xx rate
- request latency
- restart count
- service availability
- CPU saturation
- memory saturation

Do not correlate every metric initially.

Start with a small reliable set.

## Impact model

Example:

```text
ChangeImpact
────────────────────
changeId
serviceId
impactType
confidence
detectedAt
windowStart
windowEnd
evidence
status
```

Impact types:

```text
NO_SIGNIFICANT_IMPACT
POSSIBLE_NEGATIVE_IMPACT
LIKELY_NEGATIVE_IMPACT
RECOVERED
UNKNOWN
```

---

# 18. AI Audit Model

AI involvement should be modeled explicitly.

Example:

```text
AIActor
────────────────────────
id
provider
agentName
modelProvider
modelName
modelVersion
```

Example action:

```text
AIAction
────────────────────────
id
actorId
organizationId
repositoryId
pullRequestId
actionType
toolsUsed
permissionsUsed
approvedBy
approvalStatus
createdAt
```

Possible action types:

```text
CODE_GENERATION
CODE_REVIEW
TEST_GENERATION
INFRA_CHANGE
DEPLOYMENT_PROPOSAL
INCIDENT_ANALYSIS
REMEDIATION_PROPOSAL
```

---

# 19. API Design

Start with REST.

Example endpoints:

```text
GET /api/v1/services
GET /api/v1/services/{id}

GET /api/v1/changes
GET /api/v1/changes/{id}

GET /api/v1/deployments
GET /api/v1/deployments/{id}

GET /api/v1/changes/{id}/impact
GET /api/v1/changes/{id}/timeline

GET /api/v1/audit/events

POST /api/v1/integrations/github
POST /api/v1/integrations/jenkins
POST /api/v1/integrations/prometheus
```

Version all APIs.

```text
/api/v1
```

---

# 20. Security

Security must be built into the architecture early.

## V1

- TLS everywhere
- JWT/OAuth2 authentication
- RBAC
- encrypted secrets
- webhook signature verification
- database encryption at rest
- audit logging
- least-privilege connector permissions
- rate limiting

## V2

- enterprise SSO
- OIDC
- SAML
- SCIM
- finer-grained authorization
- organization isolation
- service-account identities
- agent permissions
- approval workflows
- policy engine
- secret rotation
- tenant-aware encryption boundaries

## Integration credentials

Never store raw provider credentials in source control.

Use:

```text
AWS Secrets Manager
or
Kubernetes Secrets + KMS
```

depending on environment.

---

# 21. Observability

ChangeGuard should observe itself from the beginning.

Every service should expose:

```text
/actuator/health
/actuator/prometheus
```

Metrics should include:

- request latency
- error rate
- Kafka consumer lag
- producer failures
- consumer failures
- retry count
- dead-letter count
- event processing latency
- correlation duration
- DB latency
- cache hit ratio
- external integration failures

## Distributed tracing

Use OpenTelemetry.

A lifecycle event should preserve trace/correlation identifiers.

Example:

```text
GitHub webhook
   ↓
Connector
   ↓
Kafka
   ↓
Change Service
   ↓
Correlation Service
```

The trace should remain discoverable across the flow.

## Logging

Use structured logs.

Recommended format:

```json
{
  "timestamp": "...",
  "service": "correlation-service",
  "level": "INFO",
  "traceId": "...",
  "eventId": "...",
  "organizationId": "...",
  "message": "Change impact detected"
}
```

Avoid unstructured production logs.

---

# 22. Reliability and Resilience

Services must tolerate partial failures.

Patterns:

- timeouts
- retries with backoff
- circuit breakers
- bulkheads where appropriate
- dead-letter topics
- idempotent consumers
- transactional outbox
- health checks
- graceful shutdown
- backpressure awareness

## Retry strategy

Not every failure should be retried forever.

Example:

```text
transient failure
    ↓
retry
    ↓
retry
    ↓
retry
    ↓
dead-letter topic
```

Permanent validation errors should fail fast.

---

# 23. Local Development

Local development should be possible with Docker Compose.

Recommended services:

```text
frontend
api-gateway
connector-service
change-service
correlation-service
audit-service
postgres
redis
kafka
schema-registry
prometheus
grafana
```

Optional local AI service in V2.

---

# 24. Repository Structure

Recommended monorepo:

```text
changeguard/
│
├── frontend/
│   ├── src/
│   │   ├── app/
│   │   ├── components/
│   │   ├── features/
│   │   ├── hooks/
│   │   ├── pages/
│   │   ├── services/
│   │   └── types/
│   ├── package.json
│   └── Dockerfile
│
├── backend/
│   ├── services/
│   │   ├── connector-service/
│   │   ├── change-service/
│   │   ├── correlation-service/
│   │   ├── audit-service/
│   │   ├── query-service/
│   │   └── ai-service/              # V2
│   │
│   ├── shared/
│   │   ├── event-contracts/
│   │   ├── observability/
│   │   ├── security/
│   │   └── testing/
│   │
│   └── pom.xml
│
├── event-schemas/
│   ├── code/
│   ├── build/
│   ├── deployment/
│   ├── runtime/
│   ├── ai/
│   └── correlation/
│
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   ├── terraform/
│   ├── kafka/
│   ├── observability/
│   └── aws/
│
├── docs/
│   ├── architecture/
│   ├── adr/
│   ├── api/
│   ├── event-catalog/
│   ├── security/
│   └── runbooks/
│
├── scripts/
│
├── docker-compose.yml
├── Makefile
├── README.md
└── LICENSE
```

---

# 25. CI/CD

Each service should have independent build and test stages.

Example pipeline:

```text
checkout
   ↓
compile
   ↓
unit tests
   ↓
integration tests
   ↓
contract tests
   ↓
static analysis
   ↓
security scan
   ↓
container build
   ↓
container scan
   ↓
publish image
   ↓
deploy staging
   ↓
smoke test
```

Recommended tools may include:

```text
GitHub Actions
Maven
JUnit
Testcontainers
SonarQube
Trivy
Docker
Kubernetes
Terraform
```

---

# 26. Deployment Strategy

## V1

Recommended:

```text
Docker Compose
for local development

Kubernetes
for staging/production

AWS
for cloud deployment
```

## AWS direction

Potential architecture:

```text
Amazon EKS
Amazon MSK or managed Kafka provider
Amazon RDS PostgreSQL
Amazon ElastiCache Redis
Amazon ECR
AWS Secrets Manager
AWS KMS
Amazon S3
CloudWatch
```

Prometheus/Grafana may run via managed or self-managed deployment.

## Kafka production

Do not begin by self-hosting a complex Kafka cluster unless required.

Prefer:

```text
Amazon MSK
or
Confluent Cloud
```

for early production.

---

# 27. Testing Strategy

Testing must include more than unit tests.

## Unit tests

Examples:

- correlation rules
- event validation
- mapping logic
- domain services

## Integration tests

Use Testcontainers for:

```text
PostgreSQL
Kafka
Redis
```

## Contract tests

Validate:

```text
event producer
↕
event schema
↕
event consumer
```

Schema compatibility is a production concern.

## End-to-end tests

Example:

```text
GitHub webhook
    ↓
connector
    ↓
Kafka
    ↓
change service
    ↓
deployment event
    ↓
runtime signal
    ↓
correlation
    ↓
UI result
```

## Performance tests

Test:

- Kafka throughput
- consumer lag
- correlation latency
- API latency
- PostgreSQL query performance
- high-event-volume scenarios

---

# 28. V1 Delivery Plan

V1 should be built in stages.

## Phase 1 — Platform foundation

Build:

- monorepo structure
- Spring Boot parent project
- frontend shell
- Docker Compose
- PostgreSQL
- Redis
- Kafka
- Schema Registry
- basic observability

Completion criteria:

- all services start locally
- Kafka topic can accept a test event
- metrics visible in Prometheus
- basic dashboard visible in Grafana

## Phase 2 — Canonical event system

Build:

- event envelope
- Avro schemas
- schema registry integration
- producer library
- consumer library
- event versioning rules

Completion criteria:

- versioned events can be produced and consumed
- incompatible schema changes are rejected
- duplicate events are safely handled

## Phase 3 — GitHub integration

Build:

- webhook endpoint
- signature validation
- PR event normalization
- commit event normalization

Completion criteria:

- merged PR appears as canonical code event
- event stored and visible in UI

## Phase 4 — CI integration

Build:

- Jenkins or GitHub Actions connector
- build event normalization
- commit-to-build linking

Completion criteria:

```text
PR
↓
Commit
↓
Build
```

can be reconstructed.

## Phase 5 — Deployment tracking

Build:

- deployment event model
- deployment persistence
- change record generation

Completion criteria:

```text
PR
↓
Commit
↓
Build
↓
Deployment
```

appears as one timeline.

## Phase 6 — Prometheus integration

Build:

- metric ingestion
- alert ingestion
- service mapping
- runtime event normalization

Completion criteria:

- health signals link to deployed services

## Phase 7 — Correlation engine

Build:

- recent-deployment windows
- baseline calculation
- change impact rules
- confidence model
- evidence persistence

Completion criteria:

- a simulated post-deployment regression creates `ChangeImpactDetected`

## Phase 8 — AI audit foundation

Build:

- human/AI actor distinction
- AI actor model
- model metadata
- approval metadata
- audit UI

Completion criteria:

- a change can show whether it was human-created, AI-assisted, or AI-generated

## V1 exit criteria

V1 is complete when a user can:

1. connect GitHub
2. connect Jenkins/GitHub Actions
3. connect Prometheus
4. observe a deployment timeline
5. see whether health changed after the deployment
6. view the evidence
7. identify the human or AI actor involved

---

# 29. V2 Delivery Plan

V2 expands from change correlation into operational intelligence.

## Phase 1 — Incident model

Add:

```text
Incident
AffectedService
Severity
StartedAt
ResolvedAt
```

## Phase 2 — Incident correlation

Correlate:

```text
Incident
   ↓
Service
   ↓
Deployment
   ↓
Build
   ↓
Commit
   ↓
PR
```

## Phase 3 — AI investigation service

Introduce:

```text
Python
FastAPI
```

Use it for:

- timeline summarization
- evidence explanation
- suspected cause analysis
- recommended investigation steps

Every AI answer should cite internal evidence.

## Phase 4 — Release intelligence

Add evidence-based release analysis using:

- test status
- recent incidents
- service reliability
- deployment history
- change size
- AI involvement

## Phase 5 — Approval workflow

Add:

- approval requests
- service-owner approval
- audit trail
- low-risk action execution

## Phase 6 — More connectors

Potential additions:

```text
GitLab
Bitbucket
Argo CD
Kubernetes
Grafana
Datadog
PagerDuty
Jira
ServiceNow
```

Only add connectors that support validated workflows.

---

# 30. Future Evolution

Possible future platform capabilities:

## Software lifecycle graph

```text
Initiative
   ↓
Service
   ↓
Repository
   ↓
Change
   ↓
Build
   ↓
Artifact
   ↓
Deployment
   ↓
Runtime
   ↓
Incident
   ↓
Remediation
```

## AI governance

Potential capabilities:

- agent registry
- model registry
- tool authorization
- permission boundaries
- policy engine
- human approvals
- cost tracking
- execution history
- evaluation metrics

## Controlled remediation

Potential workflow:

```text
Health degradation
      ↓
investigate
      ↓
identify likely deployment
      ↓
generate remediation proposal
      ↓
request approval
      ↓
execute action
      ↓
verify health
      ↓
close loop
```

## Expanded delivery intelligence

Potential questions:

- Which deployment caused this incident?
- Which service is responsible for a customer-impacting failure?
- Which AI agent modified the affected code?
- Which changes have the highest failure rate?
- Which services repeatedly regress after releases?
- Which deployments are most likely to require rollback?
- Which engineering teams are experiencing the most delivery instability?

---

# 31. Non-Goals

V1 is not:

- a GitHub replacement
- a Jenkins replacement
- a Kubernetes replacement
- a Grafana replacement
- an incident-management replacement
- a project-management tool
- a full SDLC suite
- an autonomous production agent
- a general-purpose AI platform

The initial focus is narrow:

> **What changed? Did it hurt production? Who or what made the change?**

---

# 32. Engineering Standards

## Architecture

- domain-driven boundaries
- event-first design
- explicit service ownership
- canonical schemas
- backward-compatible event evolution
- API versioning
- idempotent consumers
- transactional outbox
- infrastructure as code

## Code

- Java 21+
- Spring Boot conventions
- strict validation
- immutable event contracts
- structured logging
- automated formatting
- static analysis
- clear exception handling

## Operations

- health checks
- metrics
- traces
- alerts
- runbooks
- SLOs
- backups
- disaster recovery planning
- secret rotation

## Security

- least privilege
- zero-trust service assumptions
- encrypted communication
- immutable audit trails
- scoped integration tokens
- isolated tenant data
- policy-controlled production actions

---

# Final Product Thesis

ChangeGuard starts with a narrow, high-value workflow:

```text
Change
   ↓
Deployment
   ↓
Production Impact
   ↓
Human / AI Attribution
```

The foundation is intentionally designed for expansion.

The combination of:

```text
React + TypeScript + MUI
Java + Spring Boot
Apache Kafka
Kafka Streams
PostgreSQL
Redis
Prometheus
Grafana
OpenTelemetry
Docker
Kubernetes
Terraform
AWS
```

provides a modern, durable architecture for an event-driven software delivery intelligence platform.

The most important architectural asset is not the dashboard.

It is the canonical, replayable stream of software lifecycle events and the relationships ChangeGuard builds across them.

That event foundation allows the product to evolve from simple deployment correlation into release intelligence, incident investigation, AI-agent governance, and policy-controlled software-delivery automation.
