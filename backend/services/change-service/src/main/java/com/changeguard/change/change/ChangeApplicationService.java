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
}
