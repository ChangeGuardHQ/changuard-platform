package com.changeguard.connector.github;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository.IntegrationStatus;
import com.changeguard.connector.persistence.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.changeguard.connector.github.GitHubWebhookTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"changeguard.outbox.enabled=false", "changeguard.github.webhook-secret=" + SECRET})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PersistentGitHubWebhookTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ConnectorIntegrationRepository integrations;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private JsonMapper json;

    @BeforeEach
    void connectRepository() {
        jdbc.sql("TRUNCATE connector.outbox_events, connector.webhook_receipts, "
                + "connector.connected_repositories, connector.integrations").update();
        integrations.createGitHubIntegration("internal-org", "integration-a", 123);
        integrations.connectRepository("internal-org", "integration-a", "12345", "changeguard/example", "main");
    }

    @Test
    void signedMergeCommitsCanonicalEventWithStoredOwnershipBeforeReturning202() throws Exception {
        Instant before = Instant.now();
        send("pull_request", "delivery-1", fixture()).andExpect(status().isAccepted());
        assertThat(countReceipts()).isEqualTo(1);
        var event = outbox.findPending(10).getFirst();
        var body = json.readTree(event.payload());
        assertThat(body.path("organizationId").asString()).isEqualTo("internal-org");
        assertThat(body.path("source").path("integrationId").asString()).isEqualTo("integration-a");
        assertThat(body.path("eventType").asString()).isEqualTo("PullRequestMerged");
        assertThat(body.path("payload").path("pullRequestTitle").asString()).isEqualTo("Fix café checkout 🌱");
        assertThat(Instant.parse(body.path("receivedAt").asString())).isBetween(before, Instant.now());
        assertThat(event.partitionKey()).isEqualTo("12345");
    }

    @Test
    void redeliveryReturns202WithoutReplacingTheFirstEnvelope() throws Exception {
        send("pull_request", "delivery-1", fixture()).andExpect(status().isAccepted());
        var original = outbox.findPending(10).getFirst();
        send("pull_request", "delivery-1", fixture()).andExpect(status().isAccepted());
        assertThat(outbox.findPending(10)).containsExactly(original);
        assertThat(countReceipts()).isEqualTo(1);
    }

    @Test
    void hmacIsCheckedBeforeParsingEvenAnInvalidJsonBody() throws Exception {
        mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request").header("X-GitHub-Delivery", "delivery-1")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64)).content("{broken"))
                .andExpect(status().isUnauthorized());
        assertNothingAccepted();
    }

    @Test
    void signedMalformedPullRequestReturns400() throws Exception {
        send("pull_request", "delivery-1", "{broken".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        assertNothingAccepted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":0}", "{\"id\":-1}", "{\"id\":\"123\"}", "{\"id\":123.5}"})
    void installationIdIsRequiredAndStrictlyTyped(String installation) throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(fixture());
        body.set("installation", json.readTree(installation));
        send("pull_request", "delivery-1", json.writeValueAsBytes(body)).andExpect(status().isBadRequest());
        assertNothingAccepted();
    }

    @ParameterizedTest
    @EnumSource(value = IntegrationStatus.class, names = {"DISCONNECTED", "REVOKED"})
    void inactiveInstallationCannotAcceptMerges(IntegrationStatus status) throws Exception {
        integrations.setIntegrationStatus("internal-org", "integration-a", status);
        send("pull_request", "delivery-1", fixture()).andExpect(status().isForbidden());
        assertNothingAccepted();
    }

    @Test
    void unknownInstallationCannotClaimAnotherOrganizationsRepository() throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(fixture());
        ((ObjectNode) body.path("installation")).put("id", 456);
        send("pull_request", "delivery-1", json.writeValueAsBytes(body)).andExpect(status().isForbidden());
        assertNothingAccepted();
    }

    @Test
    void disconnectedRepositoryCannotAcceptMerges() throws Exception {
        integrations.disconnectRepository("internal-org", "integration-a", "12345");
        send("pull_request", "delivery-1", fixture()).andExpect(status().isForbidden());
        assertNothingAccepted();
    }

    @Test
    void unselectedRepositoryCannotAcceptMerges() throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(fixture());
        ((ObjectNode) body.path("repository")).put("id", 999);
        send("pull_request", "delivery-1", json.writeValueAsBytes(body)).andExpect(status().isForbidden());
        assertNothingAccepted();
    }

    @Test
    void verifiedUnsupportedActivityReturns202WithoutAnOutboxEvent() throws Exception {
        send("push", "ignored-1", "{}".getBytes(StandardCharsets.UTF_8)).andExpect(status().isAccepted());
        byte[] opened = new String(fixture(), StandardCharsets.UTF_8)
                .replace("\"closed\"", "\"opened\"").getBytes(StandardCharsets.UTF_8);
        send("pull_request", "ignored-2", opened).andExpect(status().isAccepted());
        assertNothingAccepted();
    }

    @Test
    void outboxWriteFailureReturns503AndRollsBackTheReceipt() throws Exception {
        jdbc.sql("ALTER TABLE connector.outbox_events ADD CONSTRAINT reject_test_write CHECK (false) NOT VALID").update();
        try {
            send("pull_request", "delivery-1", fixture()).andExpect(status().isServiceUnavailable());
            assertNothingAccepted();
        } finally {
            jdbc.sql("ALTER TABLE connector.outbox_events DROP CONSTRAINT reject_test_write").update();
        }
        // A provider retry can now durably accept the delivery.
        send("pull_request", "delivery-1", fixture()).andExpect(status().isAccepted());
        assertThat(countReceipts()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "suspend"})
    void signedInstallationLifecycleCommitsReceiptAndStopsIntake(String action) throws Exception {
        byte[] body = ("{\"action\":\"" + action + "\",\"installation\":{\"id\":123,\"account\":{\"id\":99}}}")
                .getBytes(StandardCharsets.UTF_8);
        send("installation", "lifecycle-1", body).andExpect(status().isAccepted());
        assertThat(lifecycleReceipts()).isEqualTo(1);
        assertThat(integrations.findGitHubIntegration("internal-org", "integration-a").orElseThrow().status())
                .isEqualTo(action.equals("deleted") ? IntegrationStatus.REVOKED : IntegrationStatus.DISCONNECTED);
        send("pull_request", "delivery-1", fixture()).andExpect(status().isForbidden());
        // A retry must not revoke an integration again after an authorized restoration.
        integrations.setIntegrationStatus("internal-org", "integration-a", IntegrationStatus.ACTIVE);
        send("installation", "lifecycle-1", body).andExpect(status().isAccepted());
        assertThat(lifecycleReceipts()).isEqualTo(1);
        assertThat(integrations.findActiveGitHubInstallation(123)).isPresent();
        assertNothingAccepted();
    }

    @Test
    void repositoryRemovalUsesStoredScopeAndDoesNotAffectAnotherOrganization() throws Exception {
        integrations.createGitHubIntegration("org-b", "integration-b", 456);
        integrations.connectRepository("org-b", "integration-b", "12345", "other/example", "main");
        byte[] body = "{\"action\":\"removed\",\"installation\":{\"id\":123},\"repositories_removed\":[{\"id\":12345}]}"
                .getBytes(StandardCharsets.UTF_8);
        send("installation_repositories", "removed-1", body).andExpect(status().isAccepted());
        assertThat(lifecycleReceipts()).isEqualTo(1);
        assertThat(integrations.findActiveRepository("internal-org", "integration-a", "12345")).isEmpty();
        assertThat(integrations.findActiveRepository("org-b", "integration-b", "12345")).isPresent();
        send("pull_request", "delivery-1", fixture()).andExpect(status().isForbidden());
        assertNothingAccepted();
    }

    @Test
    void lifecycleWriteFailureRollsBackReceiptAndAccessChangeBefore503() throws Exception {
        byte[] body = "{\"action\":\"suspend\",\"installation\":{\"id\":123,\"account\":{\"id\":99}}}"
                .getBytes(StandardCharsets.UTF_8);
        jdbc.sql("ALTER TABLE connector.integrations ADD CONSTRAINT reject_lifecycle CHECK (status = 'ACTIVE') NOT VALID").update();
        try {
            send("installation", "lifecycle-1", body).andExpect(status().isServiceUnavailable());
            assertThat(lifecycleReceipts()).isZero();
            assertThat(integrations.findActiveGitHubInstallation(123)).isPresent();
        } finally {
            jdbc.sql("ALTER TABLE connector.integrations DROP CONSTRAINT reject_lifecycle").update();
        }
        send("installation", "lifecycle-1", body).andExpect(status().isAccepted());
        assertThat(lifecycleReceipts()).isEqualTo(1);
    }

    @Test
    void verifiedProviderAccountCannotBeSpoofedByMergedOrLifecyclePayload() throws Exception {
        jdbc.sql("""
                UPDATE connector.integrations SET github_account_id = 99,
                    github_account_login = 'changeguard', github_account_type = 'Organization'
                """).update();
        ObjectNode body = (ObjectNode) json.readTree(fixture());
        ((ObjectNode) body.path("repository").path("owner")).put("id", 200);
        send("pull_request", "wrong-owner", json.writeValueAsBytes(body)).andExpect(status().isForbidden());
        send("installation", "wrong-account", "{\"action\":\"deleted\",\"installation\":{\"id\":123,\"account\":{\"id\":200}}}"
                .getBytes(StandardCharsets.UTF_8)).andExpect(status().isForbidden());
        assertThat(lifecycleReceipts()).isZero();
        assertThat(integrations.findActiveGitHubInstallation(123)).isPresent();
        assertNothingAccepted();
        send("pull_request", "correct-owner", fixture()).andExpect(status().isAccepted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{\"action\":\"deleted\",\"installation\":{\"id\":\"123\"}}",
            "{\"action\":\"deleted\",\"installation\":{\"id\":123}}"})
    void malformedSupportedLifecycleIsRejectedWithoutWrites(String body) throws Exception {
        send("installation", "bad-lifecycle", body.getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        assertThat(lifecycleReceipts()).isZero();
        assertThat(integrations.findActiveGitHubInstallation(123)).isPresent();
        assertNothingAccepted();
    }

    private int lifecycleReceipts() {
        return jdbc.sql("SELECT count(*) FROM connector.webhook_receipts WHERE provider_action IS NOT NULL")
                .query(Integer.class).single();
    }

    @Test
    void mergeAndLifecycleUseOneDeliveryDeduplicationLedger() throws Exception {
        send("pull_request", "shared-delivery", fixture()).andExpect(status().isAccepted());
        byte[] body = "{\"action\":\"suspend\",\"installation\":{\"id\":123,\"account\":{\"id\":99}}}"
                .getBytes(StandardCharsets.UTF_8);
        send("installation", "shared-delivery", body).andExpect(status().isAccepted());
        assertThat(countReceipts()).isEqualTo(1);
        assertThat(lifecycleReceipts()).isZero();
        assertThat(integrations.findActiveGitHubInstallation(123)).isPresent();
    }

    private ResultActions send(String event, String delivery, byte[] body) throws Exception {
        return mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event).header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", signature(body)).content(body));
    }

    private int countReceipts() {
        return jdbc.sql("SELECT count(*) FROM connector.webhook_receipts WHERE provider_event_type = 'pull_request'")
                .query(Integer.class).single();
    }

    private void assertNothingAccepted() {
        assertThat(countReceipts()).isZero();
        assertThat(outbox.findPending(10)).isEmpty();
    }
}
