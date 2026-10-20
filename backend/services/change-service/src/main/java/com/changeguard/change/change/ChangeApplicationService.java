package com.changeguard.change.change;

import com.changeguard.change.exception.EventProcessingException;
import com.changeguard.change.idempotency.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates one database transaction; Kafka acknowledgment happens only after it returns. */
@Service
public class ChangeApplicationService {
    private final ChangeRecordRepository changes;
    private final ChangeMapper mapper;
    private final IdempotencyService idempotency;
    private final com.changeguard.change.observability.ProjectionMetrics metrics;
    private final ChangeTimelineRepository timeline;

    public ChangeApplicationService(ChangeRecordRepository changes, ChangeMapper mapper, IdempotencyService idempotency,
            com.changeguard.change.observability.ProjectionMetrics metrics, ChangeTimelineRepository timeline) {
        this.changes = changes;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.timeline = timeline;
    }

    @Transactional
    public ChangeRecord ingest(MergedChange merge) {
        var receipt = idempotency.previouslyProcessed(merge);
        if (receipt.isPresent()) {
            metrics.committed(true);
            return changes.findByOrganizationIdAndId(merge.organizationId(), receipt.get().changeId()).orElseThrow();
        }
        // The natural-key constraint also deduplicates distinct deliveries/integrations of one PR merge.
        changes.insertIfAbsent(mapper.newRecord(merge));
        var stored = changes.lockPullRequest(
                merge.organizationId(), merge.source().provider(), merge.repositoryId(), merge.pullRequestNumber())
                .orElseThrow();
        stored.acceptMerge(merge);
        // A receipt failure rolls back a newly inserted projection as well; there is no pre-commit side effect.
        timeline.appendMerge(stored.getId(), merge);
        idempotency.record(merge, stored.getId());
        metrics.committed(false);
        return stored;
    }

    @Transactional
    public ChangeRecord ingestLifecycle(LifecycleChange fact) {
        var receipt = idempotency.previouslyProcessed(fact);
        if (receipt.isPresent()) {
            metrics.committed(true);
            return changes.findByOrganizationIdAndId(fact.organizationId(), receipt.get().changeId()).orElseThrow();
        }
        ChangeRecord stored;
        if (fact.type() == LifecycleChange.Type.PullRequestOpened) {
            changes.insertIfAbsent(new ChangeRecord(fact));
            stored = changes.lockPullRequest(fact.organizationId(), fact.source().provider(),
                    fact.repositoryId(), fact.pullRequestNumber()).orElseThrow();
            stored.acceptOpening(fact);
        } else {
            changes.insertCommitIfAbsent(new ChangeRecord(fact));
            stored = changes.findByOrganizationIdAndProviderAndRepositoryIdAndKindAndCommitSha(
                    fact.organizationId(), fact.source().provider(), fact.repositoryId(), ChangeKind.COMMIT, fact.commitSha())
                    .orElseThrow();
            if (!stored.getActivityAt().equals(fact.occurredAt()))
                throw new EventProcessingException("Commit already has a different occurrence time");
        }
        timeline.append(fact.organizationId(), stored.getId(), fact.type() == LifecycleChange.Type.PullRequestOpened
                        ? "open" : fact.commitSha(), fact.source().integrationId(),
                new TimelineFact(fact.eventId(), fact.type().name(), fact.occurredAt(), fact.commitSha(), fact.actor()));
        idempotency.record(fact, stored.getId());
        metrics.committed(false);
        return stored;
    }

    @Transactional(readOnly = true)
    public ChangeRecord find(String organization, java.util.UUID id) {
        return changes.findByOrganizationIdAndId(organization, id)
                .orElseThrow(com.changeguard.change.exception.ChangeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public com.changeguard.change.api.dto.ChangeTimelineResponse timeline(String organization, java.util.UUID id) {
        // Return 404 for an unavailable scoped change, including when its timeline is empty.
        return mapper.timeline(find(organization, id), timeline.find(organization, id));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ChangeRecord> list(String organization, String repository, int page, int size) {
        org.springframework.util.Assert.isTrue(page >= 0 && page <= 10000 && size >= 1 && size <= 100,
                "Page must be 0..10000 and size must be 1..100");
        if (repository != null) org.springframework.util.Assert.hasText(repository, "Repository must be nonblank");
        // A UUID tie-breaker makes pages deterministic when multiple changes share an occurrence time.
        var order = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "activityAt")
                .and(org.springframework.data.domain.Sort.by("id"));
        return changes.findPage(organization, repository, org.springframework.data.domain.PageRequest.of(page, size, order));
    }

}
