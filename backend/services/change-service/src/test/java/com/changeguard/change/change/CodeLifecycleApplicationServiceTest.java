package com.changeguard.change.change;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.changeguard.change.AvroFixtures;
import com.changeguard.change.MergeFixtures;
import com.changeguard.change.PostgresTestConfiguration;
import com.changeguard.change.exception.EventProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/** Real row locks and transactions protect monotonic lifecycle transitions during concurrent redelivery. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CodeLifecycleApplicationServiceTest {
    @Autowired ChangeApplicationService service;
    @Autowired ChangeMapper mapper;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() { jdbc.sql("TRUNCATE change.change_timeline, change.processed_events, change.change_records").update(); }

    @Test
    void openingPromotesToMergeWithTheSameIdAndPreservesBothFacts() {
        var opening = service.ingestLifecycle(opened("open", "org"));
        assertThat(opening.getStatus()).isEqualTo(ChangeStatus.OPEN);
        assertThat(opening.getMergedAt()).isNull();
        var merged = service.ingest(merge("merge", "org"));
        assertThat(merged.getId()).isEqualTo(opening.getId());
        assertThat(merged.getStatus()).isEqualTo(ChangeStatus.MERGED);
        assertThat(merged.getCommitSha()).isEqualTo(MergeFixtures.SHA);
        assertThat(merged.getHeadCommitSha()).isEqualTo("b".repeat(40));
        assertThat(service.timeline("org", merged.getId()).entries()).extracting(entry -> entry.type())
                .containsExactly("PullRequestOpened", "PullRequestMerged");
    }

    @Test
    void lateOpeningEnrichesHistoryWithoutDowngradingMergeOrItsAttribution() {
        var merged = service.ingest(merge("merge", "org"));
        var enriched = service.ingestLifecycle(opened("late-open", "org"));
        assertThat(enriched.getId()).isEqualTo(merged.getId());
        assertThat(enriched.getStatus()).isEqualTo(ChangeStatus.MERGED);
        assertThat(enriched.getSourceEventId()).isEqualTo("merge");
        assertThat(enriched.getActivityAt()).isEqualTo(MergeFixtures.MERGED_AT);
        service.ingestLifecycle(opened("late-open", "org"));
        service.ingestLifecycle(opened("another-delivery", "org"));
        assertThat(service.timeline("org", merged.getId()).entries()).hasSize(2);
        var impossible = AvroFixtures.opened("impossible", "org");
        impossible.setOccurredAt(MergeFixtures.MERGED_AT.plusSeconds(1));
        assertThatThrownBy(() -> service.ingestLifecycle(mapper.fromLifecycle(impossible)))
                .isInstanceOf(EventProcessingException.class);
    }

    @Test
    void standaloneCommitNeverClaimsAPrEvenWhenItsShaMatchesTheMerge() {
        var pr = service.ingest(merge("merge", "org"));
        var commit = service.ingestLifecycle(mapper.fromLifecycle(AvroFixtures.commit("commit", "org")));
        assertThat(commit.getId()).isNotEqualTo(pr.getId());
        assertThat(commit.getKind()).isEqualTo(ChangeKind.COMMIT);
        assertThat(commit.getStatus()).isEqualTo(ChangeStatus.COMMITTED);
        assertThat(commit.getPullRequestNumber()).isNull();
        assertThat(commit.getMergedAt()).isNull();
        assertThat(service.ingestLifecycle(mapper.fromLifecycle(AvroFixtures.commit("other-push", "org"))).getId())
                .isEqualTo(commit.getId());
        assertThat(service.timeline("org", commit.getId()).entries()).hasSize(1);
        assertThat(service.ingestLifecycle(mapper.fromLifecycle(AvroFixtures.commit("commit", "other-org"))).getId())
                .isNotEqualTo(commit.getId());
        assertThat(service.list("org", null, 0, 20).getContent()).extracting(ChangeRecord::getId)
                .containsExactly(pr.getId(), commit.getId());
    }

    @Test
    void eventIdCannotBeReusedAcrossLifecycleTypesAndCommitOccurrenceIsImmutable() {
        service.ingest(merge("reused", "org"));
        assertThatThrownBy(() -> service.ingestLifecycle(opened("reused", "org")))
                .isInstanceOf(EventProcessingException.class);
        service.ingestLifecycle(mapper.fromLifecycle(AvroFixtures.commit("commit", "org")));
        var conflicting = AvroFixtures.commit("different-delivery", "org");
        conflicting.setOccurredAt(conflicting.getOccurredAt().plusSeconds(1));
        assertThatThrownBy(() -> service.ingestLifecycle(mapper.fromLifecycle(conflicting)))
                .isInstanceOf(EventProcessingException.class);
    }

    @Test
    void receiptFailureRollsBackAnExistingPrPromotionAndItsNewTimelineFact() {
        var opened = service.ingestLifecycle(opened("open", "org"));
        jdbc.sql("ALTER TABLE change.processed_events ADD CONSTRAINT reject_promotion CHECK (event_id <> 'reject')").update();
        try {
            assertThatThrownBy(() -> service.ingest(merge("reject", "org"))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.find("org", opened.getId()).getStatus()).isEqualTo(ChangeStatus.OPEN);
            assertThat(service.timeline("org", opened.getId()).entries()).hasSize(1);
        } finally { jdbc.sql("ALTER TABLE change.processed_events DROP CONSTRAINT reject_promotion").update(); }
    }

    @Test
    void concurrentOpeningAndMergeConvergeToOneMergedPrAndTwoTimelineFacts() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<ChangeRecord>>();
            for (int index = 0; index < 8; index++) {
                boolean opening = index % 2 == 0;
                tasks.add(pool.submit(() -> {
                    start.await();
                    return opening ? service.ingestLifecycle(opened("open", "org")) : service.ingest(merge("merge", "org"));
                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var task : tasks) ids.add(task.get(20, TimeUnit.SECONDS).getId());
            assertThat(ids).hasSize(1);
            var id = ids.iterator().next();
            assertThat(service.find("org", id).getStatus()).isEqualTo(ChangeStatus.MERGED);
            assertThat(service.timeline("org", id).entries()).hasSize(2);
            assertThat(jdbc.sql("SELECT count(*) FROM change.processed_events").query(Long.class).single()).isEqualTo(2);
        }
    }

    private LifecycleChange opened(String id, String organization) { return mapper.fromLifecycle(AvroFixtures.opened(id, organization)); }
    private MergedChange merge(String id, String organization) { return mapper.fromAvro(AvroFixtures.merged(id, organization)); }
}
