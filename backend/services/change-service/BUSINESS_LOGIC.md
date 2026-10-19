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
