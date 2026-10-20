package com.changeguard.change.change;

import java.util.UUID;
import javax.sql.DataSource;
import com.changeguard.change.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.changeguard.change.MergeFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** Verify upgrades and atomic timeline storage against PostgreSQL, not a simulated SQL dialect. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ChangeTimelinePersistenceTest {
    @Autowired ChangeApplicationService service;
    @Autowired ChangeTimelineRepository timeline;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void reset() { jdbc.sql("TRUNCATE change.change_timeline, change.processed_events, change.change_records").update(); }

    @Test
    void migrationBackfillsExistingV2RowsWithoutChangingTheirOriginalIdentity() {
        var original = service.ingest(merged("original-event", "org", "repo", 7));
        try {
            // An isolated schema models a populated installation that has only applied V1/V2.
            Flyway.configure().dataSource(dataSource).defaultSchema("timeline_upgrade").schemas("timeline_upgrade")
                    .locations("classpath:db/migration").target("2").load().migrate();
            jdbc.sql("INSERT INTO timeline_upgrade.change_records SELECT * FROM change.change_records").update();
            Flyway.configure().dataSource(dataSource).defaultSchema("timeline_upgrade").schemas("timeline_upgrade")
                    .locations("classpath:db/migration").target("3").load().migrate();
            assertThat(jdbc.sql("SELECT event_id FROM timeline_upgrade.change_timeline WHERE change_id = :id")
                    .param("id", original.getId()).query(String.class).single()).isEqualTo("original-event");
            assertThat(jdbc.sql("SELECT occurred_at FROM timeline_upgrade.change_timeline")
                    .query((rs, row) -> rs.getTimestamp(1).toInstant()).single()).isEqualTo(MERGED_AT);
        } finally { jdbc.sql("DROP SCHEMA IF EXISTS timeline_upgrade CASCADE").update(); }
    }

    @Test
    void rejectedTimelineWriteRollsBackProjectionAndReceipt() {
        jdbc.sql("ALTER TABLE change.change_timeline ADD CONSTRAINT reject_timeline CHECK (event_id <> 'reject')").update();
        try {
            assertThatThrownBy(() -> service.ingest(merged("reject", "org", "repo", 7)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.sql("SELECT count(*) FROM change.change_records").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM change.processed_events").query(Long.class).single()).isZero();
        } finally { jdbc.sql("ALTER TABLE change.change_timeline DROP CONSTRAINT reject_timeline").update(); }
    }

    @Test
    void timelineCannotCrossOrganizationOwnershipAndScopedReadsDoNotRevealIt() {
        var change = service.ingest(merged("merge", "org", "repo", 7));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            timeline.append("other-org", change.getId(), "merge", "integration",
                    new TimelineFact("forged", "PullRequestMerged", MERGED_AT, SHA, null));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.timeline("other-org", change.getId()))
                .isInstanceOf(com.changeguard.change.exception.ChangeNotFoundException.class);
        assertThatThrownBy(() -> service.timeline("org", UUID.randomUUID()))
                .isInstanceOf(com.changeguard.change.exception.ChangeNotFoundException.class);
    }
}
