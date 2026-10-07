package com.changeguard.connector.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.event.CodeChangeMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "changeguard.outbox.enabled=false")
@Import(PostgresTestConfiguration.class)
class ConnectorMigrationUpgradeTests {

    // The checksum of V1 as originally committed and deployed. Editing that
    // migration must fail this test rather than silently require Flyway repair.
    private static final int ORIGINAL_V1_CHECKSUM = -765863523;
    private static final String ORGANIZATION = "org-upgrade";
    private static final String INTEGRATION = "integration-upgrade";
    private static final String REPOSITORY = "12345";
    private static final Instant RECEIVED = Instant.parse("2026-10-07T17:00:00Z");

    @Autowired private PostgreSQLContainer postgres;
    @Autowired private JdbcClient admin;
    @Autowired private JsonMapper json;

    @ParameterizedTest
    @ValueSource(strings = {"1", "2"})
    void upgradePreservesAcceptedDeliveriesAndSupportsBothIntakeVersions(String previousVersion) {
        // Use a separate database so the application context and other tests
        // retain their current schema. The identifier contains only our prefix
        // and UUID hex digits, never external input.
        String database = "migration_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.sql("CREATE DATABASE " + database).update();
        try {
            String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
            var dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Flyway.configure().dataSource(dataSource).defaultSchema("connector").schemas("connector")
                    .locations("classpath:db/migration").target(previousVersion).load().migrate();
            assertOriginalV1Checksum(jdbc);

            var integrations = new ConnectorIntegrationRepository(jdbc);
            integrations.createGitHubIntegration(ORGANIZATION, INTEGRATION, 1001);
            integrations.connectRepository(ORGANIZATION, INTEGRATION, REPOSITORY, "acme/service", "main");
            CodeChangeMergedEvent original = event("accepted-before-upgrade");
            UUID receipt = UUID.randomUUID();
            assertThat(insertLegacyReceipt(jdbc, receipt, original)).isEqualTo(1);
            jdbc.sql("""
                    INSERT INTO connector.outbox_events
                        (event_id, receipt_id, organization_id, integration_id, event_type, event_version,
                         topic, partition_key, payload)
                    VALUES (:event, :receipt, :organization, :integration, :type, :version,
                            'changeguard.code-events', :repository, CAST(:payload AS jsonb))
                    """)
                    .param("event", original.eventId()).param("receipt", receipt)
                    .param("organization", ORGANIZATION).param("integration", INTEGRATION)
                    .param("type", original.eventType()).param("version", original.eventVersion())
                    .param("repository", REPOSITORY).param("payload", json.writeValueAsString(original)).update();

            Flyway latest = Flyway.configure().dataSource(dataSource).defaultSchema("connector")
                    .schemas("connector").locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(3 - Integer.parseInt(previousVersion));
            latest.validate();
            assertOriginalV1Checksum(jdbc);
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("3");
            assertThat(latest.migrate().migrationsExecuted).isZero();

            var pending = new OutboxEventRepository(jdbc).findPending(10);
            assertThat(pending).hasSize(1);
            assertThat(pending.getFirst().eventId()).isEqualTo(original.eventId());
            assertThat(json.readTree(pending.getFirst().payload())).isEqualTo(json.valueToTree(original));
            assertThat(jdbc.sql("SELECT received_at FROM connector.webhook_receipts")
                    .query((rs, row) -> rs.getTimestamp(1).toInstant()).single()).isEqualTo(RECEIVED);

            // Old instances still use the two-column key during a rolling upgrade.
            assertThat(insertLegacyReceipt(jdbc, UUID.randomUUID(), original)).isZero();
            assertThat(insertLegacyReceipt(jdbc, UUID.randomUUID(), event("old-instance-after-upgrade")))
                    .isEqualTo(1);

            var intake = new ConnectorIntakeStore(jdbc, json, "changeguard.code-events");
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            Boolean redeliveryAccepted = transaction.execute(status -> intake.accept(original));
            Boolean newDeliveryAccepted = transaction.execute(
                    status -> intake.accept(event("new-instance-after-upgrade")));
            assertThat(redeliveryAccepted).isFalse();
            assertThat(newDeliveryAccepted).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM connector.webhook_receipts")
                    .query(Integer.class).single()).isEqualTo(3);
            assertThat(new OutboxEventRepository(jdbc).findPending(10)).hasSize(2);
        } finally {
            admin.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
        }
    }

    private void assertOriginalV1Checksum(JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT checksum FROM connector.flyway_schema_history WHERE version = '1'")
                .query(Integer.class).single()).isEqualTo(ORIGINAL_V1_CHECKSUM);
    }

    private int insertLegacyReceipt(JdbcClient jdbc, UUID receipt, CodeChangeMergedEvent event) {
        return jdbc.sql("""
                INSERT INTO connector.webhook_receipts
                    (id, organization_id, integration_id, repository_id, delivery_id, provider_event_type, received_at)
                VALUES (:receipt, :organization, :integration, :repository, :delivery, 'pull_request', :received)
                ON CONFLICT (integration_id, delivery_id) DO NOTHING
                """)
                .param("receipt", receipt).param("organization", ORGANIZATION).param("integration", INTEGRATION)
                .param("repository", REPOSITORY).param("delivery", event.payload().deliveryId())
                .param("received", RECEIVED.atOffset(ZoneOffset.UTC)).update();
    }

    private CodeChangeMergedEvent event(String delivery) {
        var dto = new GitHubMergedPullRequest(delivery, REPOSITORY, "acme/service", 42, "Ship change",
                "1234567890abcdef1234567890abcdef12345678", "feature", "main", "merge-user", RECEIVED.minusSeconds(30));
        return CodeChangeMergedEvent.fromGitHub(dto, ORGANIZATION, INTEGRATION, RECEIVED);
    }
}
