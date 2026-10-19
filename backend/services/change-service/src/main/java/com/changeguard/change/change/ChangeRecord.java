package com.changeguard.change.change;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.persistence.*;

/** One immutable merge fact per organization, provider, repository and pull request. */
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
    private int pullRequestNumber;
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
    }

    // No setters: new lifecycle transitions must be explicit, transactional domain operations.
    public UUID getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getProvider() { return provider; }
    public String getIntegrationId() { return integrationId; }
    public String getRepositoryId() { return repositoryId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public int getPullRequestNumber() { return pullRequestNumber; }
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
