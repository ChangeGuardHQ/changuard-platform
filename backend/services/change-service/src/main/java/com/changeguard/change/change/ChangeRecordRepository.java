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

    @Query("select c from ChangeRecord c where c.organizationId = :organization "
            + "and (:repository is null or c.repositoryId = :repository)")
    org.springframework.data.domain.Page<ChangeRecord> findPage(@Param("organization") String organization,
            @Param("repository") String repository, org.springframework.data.domain.Pageable pageable);

    // Row locking serializes OPEN/MERGED transitions even when multiple consumer replicas process a replay.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChangeRecord c where c.organizationId = :organization and c.provider = :provider "
            + "and c.repositoryId = :repository and c.pullRequestNumber = :number")
    Optional<ChangeRecord> lockPullRequest(@Param("organization") String organization, @Param("provider") String provider,
            @Param("repository") String repository, @Param("number") int number);

    Optional<ChangeRecord> findByOrganizationIdAndProviderAndRepositoryIdAndKindAndCommitSha(
            String organizationId, String provider, String repositoryId, ChangeKind kind, String commitSha);

    // PostgreSQL waits for a concurrent insert on the same business identity before returning.
    String INSERT = """
            INSERT INTO change.change_records (id, organization_id, provider, integration_id, repository_id, repository_full_name, pull_request_number, title, commit_sha, source_branch, target_branch, actor_type, actor_id, merged_at, received_at, created_at, source_event_id, trace_id, upstream_change_id, status, kind, opened_at, head_commit_sha, activity_at)
            VALUES (:#{#record.id}, :#{#record.organizationId}, :#{#record.provider}, :#{#record.integrationId}, :#{#record.repositoryId}, :#{#record.repositoryFullName}, :#{#record.pullRequestNumber}, :#{#record.title}, :#{#record.commitSha}, :#{#record.sourceBranch}, :#{#record.targetBranch}, :#{#record.actorType}, :#{#record.actorId}, :#{#record.mergedAt}, :#{#record.receivedAt}, :#{#record.createdAt}, :#{#record.sourceEventId}, :#{#record.traceId}, :#{#record.upstreamChangeId}, :#{#record.status.name()}, :#{#record.kind.name()}, :#{#record.openedAt}, :#{#record.headCommitSha}, :#{#record.activityAt})
            """;

    @Modifying
    @Query(value = INSERT + "ON CONFLICT (organization_id, provider, repository_id, pull_request_number) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("record") ChangeRecord record);

    // Standalone commit identity is independent of branch, delivery, integration and any PR head/merge SHA.
    @Modifying
    @Query(value = INSERT + "ON CONFLICT (organization_id, provider, repository_id, commit_sha) WHERE kind = 'COMMIT' DO NOTHING", nativeQuery = true)
    int insertCommitIfAbsent(@Param("record") ChangeRecord record);
}
