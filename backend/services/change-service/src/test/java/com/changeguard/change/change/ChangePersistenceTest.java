package com.changeguard.change.change;

import com.changeguard.change.PostgresTestConfiguration;
import com.changeguard.change.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.changeguard.change.MergeFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** Check actual PostgreSQL constraints rather than relying on an in-memory database dialect. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class ChangePersistenceTest {
    @Autowired ChangeRecordRepository changes;
    @Autowired ProcessedEventRepository receipts;
    @Autowired ChangeMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @org.junit.jupiter.api.BeforeEach
    void isolateRowsFromOtherTestsSharingTheSpringContext() {
        // Application-service tests commit real transactions in this shared test database.
        jdbc.sql("TRUNCATE change.change_timeline, change.processed_events, change.change_records").update();
    }

    @Test
    void storesAProviderFactAndItsReceiptInsideTheOwningSchema() {
        var fact = merged("event-1", "org-one", "repo-1", 7);
        var record = mapper.newRecord(fact);
        assertThat(changes.insertIfAbsent(record)).isEqualTo(1);
        receipts.insertIfAbsent("org-one", fact.eventId(), fact.fingerprint(), record.getId());
        var stored = changes.findByOrganizationIdAndId("org-one", record.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ChangeStatus.MERGED);
        assertThat(stored.getMergedAt()).isEqualTo(MERGED_AT);
        assertThat(stored.getActorId()).isNull();
        assertThat(receipts.find("org-one", fact.eventId()).orElseThrow().changeId()).isEqualTo(stored.getId());
        assertThat(changes.findByOrganizationIdAndId("other-org", stored.getId())).isEmpty();
    }

    @Test
    void oneMergeCannotBeInsertedTwiceButOtherOrganizationsRemainIndependent() {
        assertThat(changes.insertIfAbsent(mapper.newRecord(merged("one", "org-one", "repo-1", 7)))).isEqualTo(1);
        assertThat(changes.insertIfAbsent(mapper.newRecord(merged("two", "org-one", "repo-1", 7)))).isZero();
        assertThat(changes.insertIfAbsent(mapper.newRecord(merged("three", "org-two", "repo-1", 7)))).isEqualTo(1);
        assertThat(changes.count()).isEqualTo(2);
    }

    @Test
    void receiptCannotReferenceAnotherOrganizationsChange() {
        var record = mapper.newRecord(merged("one", "org-one", "repo-1", 7));
        changes.insertIfAbsent(record);
        assertThatThrownBy(() -> receipts.insertIfAbsent("org-two", "one", "a".repeat(64), record.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void receiptTimeDoesNotChangeReplayIdentity() {
        var fact = merged("event", "org", "repo", 1);
        var replay = new MergedChange(fact.eventId(), fact.organizationId(), fact.source(), fact.deliveryId(),
                fact.repositoryId(), fact.repositoryFullName(), fact.pullRequestNumber(), fact.title(), fact.commitSha(),
                fact.sourceBranch(), fact.targetBranch(), fact.actor(), fact.mergedAt(), fact.receivedAt().plusSeconds(1),
                fact.traceId(), fact.upstreamChangeId());
        assertThat(replay.fingerprint()).isEqualTo(fact.fingerprint());
    }
}
