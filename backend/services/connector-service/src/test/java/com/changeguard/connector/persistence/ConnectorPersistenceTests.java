package com.changeguard.connector.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.event.CodeChangeMergedEvent;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.IntegrationStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "changeguard.outbox.enabled=false")
@Import(PostgresTestConfiguration.class)
class ConnectorPersistenceTests {

    private static final String ORGANIZATION = "org-a";
    private static final String INTEGRATION = "integration-a";
    private static final String REPOSITORY = "9007199254740993";
    private static final Instant RECEIVED = Instant.parse("2026-10-07T17:00:00Z");

    @Autowired private ConnectorIntegrationRepository integrations;
    @Autowired private ConnectorIntakeStore intake;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private Flyway flyway;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void prepareConnectedRepository() {
        jdbc.sql("""
                TRUNCATE connector.outbox_events, connector.webhook_receipts,
                         connector.connected_repositories, connector.integrations
                """).update();
        integrations.createGitHubIntegration(ORGANIZATION, INTEGRATION, 1001);
        integrations.connectRepository(ORGANIZATION, INTEGRATION, REPOSITORY, "acme/service", "main");
    }

    @Test
    void migrationsAreValidatedAndNotReappliedOnRestart() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(integrations.findGitHubIntegration(ORGANIZATION, INTEGRATION)).isPresent();
    }

    @Test
    void persistsReceiptAndCanonicalEnvelopeTogetherWithoutKafka() {
        CodeChangeMergedEvent event = event("delivery-1", ORGANIZATION, INTEGRATION);
        assertThat(intake.accept(event)).isTrue();
        assertThat(count("webhook_receipts")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT received_at FROM connector.webhook_receipts")
                .query((rs, row) -> rs.getTimestamp(1).toInstant()).single()).isEqualTo(RECEIVED);
        var pending = outbox.findPending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().eventId()).isEqualTo(event.eventId());
        assertThat(pending.getFirst().topic()).isEqualTo("changeguard.code-events");
        assertThat(pending.getFirst().partitionKey()).isEqualTo(REPOSITORY);
        assertThat(json.readTree(pending.getFirst().payload())).isEqualTo(json.valueToTree(event));
    }

    @Test
    void redeliveryDoesNotReplaceOriginalReceiptOrCreateAnotherEvent() {
        CodeChangeMergedEvent first = event("delivery-1", ORGANIZATION, INTEGRATION);
        CodeChangeMergedEvent redelivery = new CodeChangeMergedEvent(first.eventId(), first.eventType(),
                first.eventVersion(), first.occurredAt(), RECEIVED.plusSeconds(60), first.organizationId(),
                first.source(), first.actor(), first.correlation(), first.payload());
        assertThat(intake.accept(first)).isTrue();
        assertThat(intake.accept(redelivery)).isFalse();
        assertThat(count("webhook_receipts")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(json.readTree(outbox.findPending(1).getFirst().payload()))
                .isEqualTo(json.valueToTree(first));
    }

    @Test
    void concurrentRedeliveryCreatesExactlyOneReceiptAndOutboxEvent() throws Exception {
        CodeChangeMergedEvent event = event("concurrent-delivery", ORGANIZATION, INTEGRATION);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return intake.accept(event);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) accepted++;
            }
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(count("webhook_receipts")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void sameDeliveryIdInDifferentIntegrationsIsNotADuplicate() {
        integrations.createGitHubIntegration("org-b", "integration-b", 1002);
        integrations.connectRepository("org-b", "integration-b", REPOSITORY, "acme/service", "main");
        assertThat(intake.accept(event("delivery-1", ORGANIZATION, INTEGRATION))).isTrue();
        assertThat(intake.accept(event("delivery-1", "org-b", "integration-b"))).isTrue();
        assertThat(count("webhook_receipts")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
    }

    @Test
    void organizationScopeProtectsReadsWritesAndIntake() {
        assertThat(integrations.findGitHubIntegration("org-b", INTEGRATION)).isEmpty();
        assertThat(integrations.findActiveRepository("org-b", INTEGRATION, REPOSITORY)).isEmpty();
        assertThat(integrations.setIntegrationStatus("org-b", INTEGRATION, IntegrationStatus.REVOKED)).isFalse();
        assertThat(integrations.disconnectRepository("org-b", INTEGRATION, REPOSITORY)).isFalse();
        assertThatThrownBy(() -> integrations.connectRepository("org-b", INTEGRATION, "other", "acme/other", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intake.accept(event("forged-context", "org-b", INTEGRATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("webhook_receipts")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void databaseRejectsCrossOrganizationOwnershipEvenWithoutRepositoryChecks() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO connector.connected_repositories
                    (integration_id, organization_id, repository_id, full_name)
                VALUES ('integration-a', 'org-b', 'other', 'acme/other')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> integrations.createGitHubIntegration("org-b", "integration-b", 1001))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = IntegrationStatus.class, names = {"DISCONNECTED", "REVOKED"})
    void inactiveIntegrationStopsRepositoryLookupAndNewIntake(IntegrationStatus status) {
        assertThat(integrations.setIntegrationStatus(ORGANIZATION, INTEGRATION, status)).isTrue();
        assertThat(integrations.findActiveRepository(ORGANIZATION, INTEGRATION, REPOSITORY)).isEmpty();
        assertThatThrownBy(() -> intake.accept(event("delivery-1", ORGANIZATION, INTEGRATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> integrations.connectRepository(ORGANIZATION, INTEGRATION, "other", "acme/other", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("webhook_receipts")).isZero();
    }

    @Test
    void disconnectedRepositoryStopsNewIntake() {
        integrations.disconnectRepository(ORGANIZATION, INTEGRATION, REPOSITORY);
        assertThatThrownBy(() -> intake.accept(event("delivery-1", ORGANIZATION, INTEGRATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("webhook_receipts")).isZero();
    }

    @Test
    void selectionIsIdempotentAndRepositoryRenameKeepsProviderIdentity() {
        integrations.connectRepository(ORGANIZATION, INTEGRATION, REPOSITORY, "acme/renamed", null);
        var repository = integrations.findActiveRepository(ORGANIZATION, INTEGRATION, REPOSITORY).orElseThrow();
        assertThat(repository.fullName()).isEqualTo("acme/renamed");
        assertThat(repository.defaultBranch()).isNull();
        assertThat(repository.repositoryId()).isEqualTo(REPOSITORY);
        assertThat(count("connected_repositories")).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackNewReceiptAndPreservesExistingDelivery() {
        CodeChangeMergedEvent first = event("delivery-1", ORGANIZATION, INTEGRATION);
        intake.accept(first);
        CodeChangeMergedEvent second = event("delivery-2", ORGANIZATION, INTEGRATION);
        CodeChangeMergedEvent collidingId = new CodeChangeMergedEvent(first.eventId(), second.eventType(),
                second.eventVersion(), second.occurredAt(), second.receivedAt(), second.organizationId(),
                second.source(), second.actor(), second.correlation(), second.payload());
        assertThatThrownBy(() -> intake.accept(collidingId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("webhook_receipts")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(intake.accept(second)).isTrue();
    }

    @Test
    void surroundingTransactionRollbackRemovesBothWrites() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            intake.accept(event("rolled-back", ORGANIZATION, INTEGRATION));
            status.setRollbackOnly();
        });
        assertThat(count("webhook_receipts")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void committedPendingEventsRemainReadableUntilPublicationIsRecorded() {
        CodeChangeMergedEvent event = event("delivery-1", ORGANIZATION, INTEGRATION);
        intake.accept(event);
        // A new repository instance reads committed rows without any in-memory receipt state.
        assertThat(new OutboxEventRepository(jdbc).findPending(10)).hasSize(1);
        var claim = outbox.claimNext(java.time.Duration.ofSeconds(60)).orElseThrow();
        assertThat(outbox.markPublished(claim)).isTrue();
        assertThat(outbox.markPublished(claim)).isFalse();
        assertThat(outbox.findPending(10)).isEmpty();
        assertThat(intake.accept(event)).isFalse();
        assertThat(count("webhook_receipts")).isEqualTo(1);
    }

    private int count(String table) {
        String sql = switch (table) {
            case "webhook_receipts" -> "SELECT count(*) FROM connector.webhook_receipts";
            case "outbox_events" -> "SELECT count(*) FROM connector.outbox_events";
            case "connected_repositories" -> "SELECT count(*) FROM connector.connected_repositories";
            default -> throw new IllegalArgumentException("Unknown test table");
        };
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private CodeChangeMergedEvent event(String delivery, String organization, String integration) {
        var dto = new GitHubMergedPullRequest(delivery, REPOSITORY, "acme/service", 42, "Ship change",
                "1234567890abcdef1234567890abcdef12345678", "feature", "main", "merge-user", RECEIVED.minusSeconds(30));
        return CodeChangeMergedEvent.fromGitHub(dto, organization, integration, RECEIVED);
    }
}
