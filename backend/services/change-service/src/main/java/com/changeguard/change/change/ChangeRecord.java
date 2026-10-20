package com.changeguard.change.change;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.*;

/** Service-owned change identity with explicit, monotonic lifecycle transitions. */
@Entity
@Table(name = "change_records", schema = "change")
public class ChangeRecord {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "organization_id")
    private String organizationId;
    @Column(name = "provider")
    private String provider;
    @Column(name = "integration_id")
    private String integrationId;
    @Column(name = "repository_id")
    private String repositoryId;
    @Column(name = "repository_full_name")
    private String repositoryFullName;
    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;
    @Column(name = "title")
    private String title;
    @Column(name = "commit_sha")
    private String commitSha;
    @Column(name = "source_branch")
    private String sourceBranch;
    @Column(name = "target_branch")
    private String targetBranch;
    @Column(name = "actor_type")
    private String actorType;
    @Column(name = "actor_id")
    private String actorId;
    @Column(name = "merged_at")
    private Instant mergedAt;
    @Column(name = "received_at")
    private Instant receivedAt;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "source_event_id")
    private String sourceEventId;
    @Column(name = "trace_id")
    private String traceId;
    @Column(name = "upstream_change_id")
    private String upstreamChangeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ChangeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private ChangeKind kind;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "head_commit_sha")
    private String headCommitSha;
    @Column(name = "activity_at")
    private Instant activityAt;

    protected ChangeRecord() { /* Required by JPA; application records are created from validated facts. */ }

    ChangeRecord(MergedChange merge) {
        this.id = UUID.randomUUID();
        this.organizationId = merge.organizationId();
        this.provider = merge.source().provider();
        this.integrationId = merge.source().integrationId();
        this.repositoryId = merge.repositoryId();
        this.repositoryFullName = merge.repositoryFullName();
        this.pullRequestNumber = merge.pullRequestNumber();
        this.title = merge.title();
        this.commitSha = merge.commitSha();
        this.sourceBranch = merge.sourceBranch();
        this.targetBranch = merge.targetBranch();
        this.actorType = merge.actor() == null ? null : merge.actor().type().name();
        this.actorId = merge.actor() == null ? null : merge.actor().id();
        this.mergedAt = merge.mergedAt();
        this.receivedAt = merge.receivedAt();
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.sourceEventId = merge.eventId();
        this.traceId = merge.traceId();
        this.upstreamChangeId = merge.upstreamChangeId();
        this.status = ChangeStatus.MERGED;
        this.kind = ChangeKind.PULL_REQUEST;
        this.activityAt = merge.mergedAt();
    }

    ChangeRecord(LifecycleChange fact) {
        this.id = UUID.randomUUID();
        this.organizationId = fact.organizationId();
        this.provider = fact.source().provider();
        this.integrationId = fact.source().integrationId();
        this.repositoryId = fact.repositoryId();
        this.repositoryFullName = fact.repositoryFullName();
        this.pullRequestNumber = fact.pullRequestNumber();
        this.title = fact.title();
        this.commitSha = fact.commitSha();
        this.sourceBranch = fact.sourceBranch();
        this.targetBranch = fact.targetBranch();
        this.actorType = fact.actor() == null ? null : fact.actor().type().name();
        this.actorId = fact.actor() == null ? null : fact.actor().id();
        this.receivedAt = fact.receivedAt();
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.sourceEventId = fact.eventId();
        this.traceId = fact.traceId();
        this.upstreamChangeId = fact.upstreamChangeId();
        this.activityAt = fact.occurredAt();
        this.kind = fact.type() == LifecycleChange.Type.PullRequestOpened ? ChangeKind.PULL_REQUEST : ChangeKind.COMMIT;
        this.status = kind == ChangeKind.PULL_REQUEST ? ChangeStatus.OPEN : ChangeStatus.COMMITTED;
        this.openedAt = kind == ChangeKind.PULL_REQUEST ? fact.occurredAt() : null;
        this.headCommitSha = kind == ChangeKind.PULL_REQUEST ? fact.commitSha() : null;
    }

    void acceptMerge(MergedChange merge) {
        if (status == ChangeStatus.MERGED) {
            require(commitSha.equals(merge.commitSha()) && mergedAt.equals(merge.mergedAt()),
                    "A merged pull request already has a different commit or merge time");
            return;
        }
        require(status == ChangeStatus.OPEN && !openedAt.isAfter(merge.mergedAt()), "Merge cannot precede PR opening");
        // Promote the same UUID to MERGED; the opening fact and its own actor stay in the timeline.
        status = ChangeStatus.MERGED;
        mergedAt = merge.mergedAt();
        activityAt = merge.mergedAt();
        commitSha = merge.commitSha();
        title = merge.title();
        repositoryFullName = merge.repositoryFullName();
        integrationId = merge.source().integrationId();
        sourceBranch = merge.sourceBranch();
        targetBranch = merge.targetBranch();
        actorType = merge.actor() == null ? null : merge.actor().type().name();
        actorId = merge.actor() == null ? null : merge.actor().id();
        sourceEventId = merge.eventId();
        traceId = merge.traceId();
        upstreamChangeId = merge.upstreamChangeId();
    }

    void acceptOpening(LifecycleChange opening) {
        if (openedAt != null) {
            require(openedAt.equals(opening.occurredAt()) && headCommitSha.equals(opening.commitSha()),
                    "A pull request already has different opening facts");
        } else {
            require(mergedAt != null && !opening.occurredAt().isAfter(mergedAt), "Opening cannot follow merge time");
            // A late opening enriches history without changing MERGED status or merge attribution.
            openedAt = opening.occurredAt();
            headCommitSha = opening.commitSha();
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new com.changeguard.change.exception.EventProcessingException(message);
    }

    // No public setters: each state transition runs under the repository's row lock and one transaction.
    public ChangeKind getKind() { return kind; }
    public Instant getOpenedAt() { return openedAt; }
    public String getHeadCommitSha() { return headCommitSha; }
    public Instant getActivityAt() { return activityAt; }
    public UUID getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getProvider() { return provider; }
    public String getIntegrationId() { return integrationId; }
    public String getRepositoryId() { return repositoryId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public Integer getPullRequestNumber() { return pullRequestNumber; }
    public String getTitle() { return title; }
    public String getCommitSha() { return commitSha; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public String getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public Instant getMergedAt() { return mergedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSourceEventId() { return sourceEventId; }
    public String getTraceId() { return traceId; }
    public String getUpstreamChangeId() { return upstreamChangeId; }
    public ChangeStatus getStatus() { return status; }
}
