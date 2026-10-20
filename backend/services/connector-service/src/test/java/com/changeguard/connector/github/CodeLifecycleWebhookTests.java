package com.changeguard.connector.github;

import com.changeguard.connector.LifecycleWebhookFixtures;
import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HMAC, ownership locks and a single receipt protect each complete opened-PR or multi-commit push delivery. */
@SpringBootTest(properties = {"changeguard.outbox.enabled=false", "changeguard.github.code-lifecycle-enabled=true",
        "changeguard.github.webhook-secret=" + SECRET})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class CodeLifecycleWebhookTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ConnectorIntegrationRepository integrations;
    @Autowired JsonMapper json;

    @BeforeEach
    void connect() {
        jdbc.sql("TRUNCATE connector.outbox_events, connector.webhook_receipts, connector.connected_repositories, connector.integrations").update();
        integrations.createGitHubIntegration("trusted-org", "integration", 123);
        integrations.connectRepository("trusted-org", "integration", "12345", "changeguard/example", "main");
    }

    @Test
    void openingAndPushAreStoredOnceUsingOwnershipFromTheConnectedInstallation() throws Exception {
        send("pull_request", "open", LifecycleWebhookFixtures.opened()).andExpect(status().isAccepted());
        for (int attempt = 0; attempt < 2; attempt++) send("push", "push", LifecycleWebhookFixtures.push())
                .andExpect(status().isAccepted());
        assertThat(count("webhook_receipts")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT DISTINCT organization_id FROM connector.outbox_events").query(String.class).list())
                .containsExactly("trusted-org");
        assertThat(jdbc.sql("SELECT event_type FROM connector.outbox_events ORDER BY event_type").query(String.class).list())
                .containsExactly("CommitCreated", "CommitCreated", "PullRequestOpened");
    }

    @Test
    void aFailureWritingTheSecondCommitRollsBackTheEntirePushReceiptAndOutbox() throws Exception {
        jdbc.sql("ALTER TABLE connector.outbox_events ADD CONSTRAINT reject_second_commit "
                + "CHECK (payload->'payload'->>'commitSha' <> '" + "b".repeat(40) + "')").update();
        try {
            send("push", "push", LifecycleWebhookFixtures.push()).andExpect(status().isServiceUnavailable());
            assertThat(count("webhook_receipts")).isZero();
            assertThat(count("outbox_events")).isZero();
        } finally { jdbc.sql("ALTER TABLE connector.outbox_events DROP CONSTRAINT reject_second_commit").update(); }
    }

    @Test
    void disconnectedRepositoryOrWrongOwnerCannotAcceptNewLifecycleTypes() throws Exception {
        jdbc.sql("UPDATE connector.connected_repositories SET status = 'DISCONNECTED'").update();
        send("push", "push", LifecycleWebhookFixtures.push()).andExpect(status().isForbidden());
        send("pull_request", "open", LifecycleWebhookFixtures.opened()).andExpect(status().isForbidden());
        assertThat(count("outbox_events")).isZero();
        jdbc.sql("UPDATE connector.connected_repositories SET status = 'ACTIVE'").update();
        jdbc.sql("UPDATE connector.integrations SET github_account_id = 99, github_account_login = 'changeguard', github_account_type = 'Organization'").update();
        var wrongOwner = (ObjectNode) json.readTree(LifecycleWebhookFixtures.push());
        ((ObjectNode) wrongOwner.path("repository").path("owner")).put("id", 100);
        send("push", "wrong-owner", json.writeValueAsBytes(wrongOwner)).andExpect(status().isForbidden());
        assertThat(count("webhook_receipts")).isZero();
    }

    @Test
    void invalidSignatureAndMalformedPushNeverReachStorage() throws Exception {
        mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", "push")
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64)).content(LifecycleWebhookFixtures.push()))
                .andExpect(status().isUnauthorized());
        var malformed = (ObjectNode) json.readTree(LifecycleWebhookFixtures.push());
        ((ObjectNode) malformed.path("commits").get(1)).put("timestamp", "invalid");
        send("push", "push", json.writeValueAsBytes(malformed)).andExpect(status().isBadRequest());
        assertThat(count("webhook_receipts")).isZero();
    }

    private long count(String table) { return jdbc.sql("SELECT count(*) FROM connector." + table).query(Long.class).single(); }
    private ResultActions send(String type, String id, byte[] payload) throws Exception {
        return mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", type).header("X-GitHub-Delivery", id)
                .header("X-Hub-Signature-256", signature(payload)).content(payload));
    }
}
