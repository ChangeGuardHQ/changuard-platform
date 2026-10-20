# Change Service delivery steps

The first business delivery projects the connector's existing `PullRequestMerged`
facts. Build/deployment linking and outgoing derived events require separate
contracts and will follow this slice. Every step includes inline documentation,
focused tests, and an independently buildable commit.

1. **Domain and persistence:** immutable merge facts, organization-scoped change
   records, real V1/V2 migrations and PostgreSQL constraints. Enable Flyway only
   now that the previously disabled placeholders contain DDL.
2. **Transactional ingestion:** insert one change per organization/provider/repository/PR,
   persist event receipts in the same transaction, detect conflicting replays,
   and test duplicate, rollback and concurrent delivery behavior.
3. **Read APIs:** list/detail/timeline endpoints, bounded pagination, trusted
   organization-to-reader bindings, and consistent HTTP errors.
4. **Kafka delivery:** consume the shared specific Avro contract, commit offsets
   after database success, wire Schema Registry into Compose/CI, and exercise
   serialization, replay and query results through a real broker.

The change ID belongs to this service; optional upstream correlation never selects
or overwrites it. Merge time is the provider occurrence time. The initial timeline
contains one merge fact, rather than a separate entry for every delivery.

All four steps are implemented on `feature/change-service-business-logic`.
The final wire test registers the shared schemas, produces duplicate Avro merges,
queries the timeline, rewinds the stopped consumer group to replay, and proves
an invalid semantic version leaves its offset uncommitted.

Final image validation also found `CVE-2026-77214` in the pinned JRE's native
libexpat package. The runtime Dockerfiles explicitly install Alpine's fixed
`2.9.0-r0` package, including the shared broker and Registry images used by CI.
The HIGH/CRITICAL and secret gates remain enabled.

## Follow-up PR 1: consumer recovery

`feature/change-consumer-recovery` adds bounded processing retries and a dedicated,
byte-preserving DLT. Permanent contract errors skip retries; temporary database
failures retry before quarantine. A failed quarantine publish retains the source
offset. The packaged single-offset replay tool defaults to dry run, validates
source provenance, and leaves consumer groups untouched. Broker integration tests
cover retry success, invalid Avro, later-record progress and DLT publishing failure.

## Follow-up PR 2: consumer visibility

`feature/change-consumer-visibility` adds a separate projection health group,
consumer-state gauges, commit-aware ingestion counters, recovery outcomes and
native Kafka lag metrics. Read API readiness remains tied to the database.
Tests cover rollback-safe counters, failed recovery and container state changes.

## Follow-up PR 3: persistent timeline

`feature/change-persistent-timeline` appends business facts in the same transaction
as projection and event receipts. V3 backfills existing merge records using their
original event IDs, actors and occurrence times. Scoped reads return stored facts;
duplicate deliveries keep one business entry. PostgreSQL tests cover populated V2
upgrades, tenant foreign keys and rollback when timeline or receipt writes fail.
