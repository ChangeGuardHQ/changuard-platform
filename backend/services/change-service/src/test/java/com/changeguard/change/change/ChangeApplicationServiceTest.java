package com.changeguard.change.change;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import static com.changeguard.change.MergeFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** Exercise transaction boundaries, unique-key locking and rollback against real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ChangeApplicationServiceTest {
    @Autowired ChangeApplicationService service;
    @Autowired ChangeRecordRepository changes;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() { jdbc.sql("TRUNCATE change.processed_events, change.change_records").update(); }

    @Test
    void replayReturnsTheOriginalIdAndDistinctDeliveriesDoNotCreateAnotherMerge() {
        var first = service.ingest(merged("one", "org-one", "repo", 7));
        assertThat(service.ingest(merged("one", "org-one", "repo", 7)).getId()).isEqualTo(first.getId());
        assertThat(service.ingest(merged("two", "org-one", "repo", 7)).getId()).isEqualTo(first.getId());
        assertThat(changes.count()).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(2);
    }

    @Test
    void organizationsAndPullRequestsHaveIndependentIdentities() {
        service.ingest(merged("same-event", "org-one", "repo", 7));
        service.ingest(merged("same-event", "org-two", "repo", 7));
        service.ingest(merged("other-event", "org-one", "repo", 8));
        assertThat(changes.count()).isEqualTo(3);
        assertThat(receiptCount()).isEqualTo(3);
    }

    @Test
    void changedEventContentAndContradictoryMergeFactsAreRejected() {
        service.ingest(merged("one", "org", "repo", 7));
        assertThatThrownBy(() -> service.ingest(merged("one", "org", "another-repo", 7)))
                .isInstanceOf(EventProcessingException.class);
        assertThatThrownBy(() -> service.ingest(merged("two", "org", "repo", 7, "b".repeat(40))))
                .isInstanceOf(EventProcessingException.class);
        assertThat(changes.count()).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void failedReceiptInsertRollsBackTheProjection() {
        // A real database failure after projection insertion must not leave an unprotected change.
        jdbc.sql("ALTER TABLE change.processed_events ADD CONSTRAINT reject_test_receipt CHECK (event_id <> 'reject')").update();
        try {
            assertThatThrownBy(() -> service.ingest(merged("reject", "org", "repo", 7)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(changes.count()).isZero();
            assertThat(receiptCount()).isZero();
        } finally {
            jdbc.sql("ALTER TABLE change.processed_events DROP CONSTRAINT reject_test_receipt").update();
        }
    }

    @Test
    void concurrentRedeliveriesCommitOneProjectionAndOneReceiptPerEvent() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<ChangeRecord>>();
            for (int index = 0; index < 8; index++) {
                String eventId = "concurrent-" + index % 2;
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.ingest(merged(eventId, "org", "repo", 7));
                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS).getId());
            assertThat(ids).hasSize(1);
        }
        assertThat(changes.count()).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(2);
    }

    private long receiptCount() {
        return jdbc.sql("SELECT count(*) FROM change.processed_events").query(Long.class).single();
    }
}
