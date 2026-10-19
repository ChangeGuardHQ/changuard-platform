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

    public ChangeApplicationService(ChangeRecordRepository changes, ChangeMapper mapper, IdempotencyService idempotency) {
        this.changes = changes;
        this.mapper = mapper;
        this.idempotency = idempotency;
    }

    @Transactional
    public ChangeRecord ingest(MergedChange merge) {
        var receipt = idempotency.previouslyProcessed(merge);
        if (receipt.isPresent()) {
            return changes.findByOrganizationIdAndId(merge.organizationId(), receipt.get().changeId()).orElseThrow();
        }
        // The natural-key constraint also deduplicates distinct deliveries/integrations of one PR merge.
        changes.insertIfAbsent(mapper.newRecord(merge));
        var stored = changes.findByOrganizationIdAndProviderAndRepositoryIdAndPullRequestNumber(
                merge.organizationId(), merge.source().provider(), merge.repositoryId(), merge.pullRequestNumber())
                .orElseThrow();
        if (!stored.getCommitSha().equals(merge.commitSha()) || !stored.getMergedAt().equals(merge.mergedAt())) {
            throw new EventProcessingException("A merged pull request already has a different commit or merge time");
        }
        // A receipt failure rolls back a newly inserted projection as well; there is no pre-commit side effect.
        idempotency.record(merge, stored.getId());
        return stored;
    }

    @Transactional(readOnly = true)
    public ChangeRecord find(String organization, java.util.UUID id) {
        return changes.findByOrganizationIdAndId(organization, id)
                .orElseThrow(com.changeguard.change.exception.ChangeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ChangeRecord> list(String organization, String repository, int page, int size) {
        org.springframework.util.Assert.isTrue(page >= 0 && page <= 10000 && size >= 1 && size <= 100,
                "Page must be 0..10000 and size must be 1..100");
        if (repository != null) org.springframework.util.Assert.hasText(repository, "Repository must be nonblank");
        // A UUID tie-breaker makes pages deterministic when multiple merges share an occurrence time.
        var order = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "mergedAt")
                .and(org.springframework.data.domain.Sort.by("id"));
        return changes.findPage(organization, repository, org.springframework.data.domain.PageRequest.of(page, size, order));
    }

}
