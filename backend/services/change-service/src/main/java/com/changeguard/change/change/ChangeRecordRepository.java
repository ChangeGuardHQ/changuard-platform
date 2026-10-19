package com.changeguard.change.change;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** All business lookups carry organization scope; natural-key uniqueness handles redeliveries. */
public interface ChangeRecordRepository extends JpaRepository<ChangeRecord, UUID> {
    Optional<ChangeRecord> findByOrganizationIdAndId(String organizationId, UUID id);
    Optional<ChangeRecord> findByOrganizationIdAndProviderAndRepositoryIdAndPullRequestNumber(
            String organizationId, String provider, String repositoryId, int pullRequestNumber);

    // PostgreSQL waits for a concurrent insert on the same business identity before returning.
    @Modifying
    @Query(value = """
            INSERT INTO change.change_records (id, organization_id, provider, integration_id, repository_id, repository_full_name, pull_request_number, title, commit_sha, source_branch, target_branch, actor_type, actor_id, merged_at, received_at, created_at, source_event_id, trace_id, upstream_change_id, status)
            VALUES (:#{#record.id}, :#{#record.organizationId}, :#{#record.provider}, :#{#record.integrationId}, :#{#record.repositoryId}, :#{#record.repositoryFullName}, :#{#record.pullRequestNumber}, :#{#record.title}, :#{#record.commitSha}, :#{#record.sourceBranch}, :#{#record.targetBranch}, :#{#record.actorType}, :#{#record.actorId}, :#{#record.mergedAt}, :#{#record.receivedAt}, :#{#record.createdAt}, :#{#record.sourceEventId}, :#{#record.traceId}, :#{#record.upstreamChangeId}, :#{#record.status.name()})
            ON CONFLICT (organization_id, provider, repository_id, pull_request_number) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("record") ChangeRecord record);
}
