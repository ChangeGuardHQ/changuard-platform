package com.changeguard.connector.github.connection;

import java.util.List;
import java.util.concurrent.Executors;

import com.changeguard.connector.PostgresTestConfiguration;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.GitHubLifecycleStore;
import com.changeguard.connector.github.GitHubLifecycleNormalizer.LifecycleDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static com.changeguard.connector.github.GitHubWebhookTestSupport.SECRET;
import static com.changeguard.connector.github.GitHubWebhookTestSupport.fixture;
import static com.changeguard.connector.github.GitHubWebhookTestSupport.signature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "changeguard.outbox.enabled=false", "changeguard.github.webhook-secret=" + SECRET,
        "changeguard.github.connection.enabled=true", "changeguard.github.connection.app-id=7",
        "changeguard.github.connection.app-slug=changeguard-test",
        "changeguard.github.connection.private-key-path=/unused-with-mocked-provider",
        "changeguard.github.connection.state-secret=connection-test-secret-at-least-32-bytes",
        "changeguard.github.connection.organizations[0].organization-id=internal-org",
        "changeguard.github.connection.organizations[0].github-account-id=99",
        "changeguard.github.connection.organizations[0].administrators=alice,other-admin",
        "changeguard.github.connection.organizations[1].organization-id=org-b",
        "changeguard.github.connection.organizations[1].github-account-id=200",
        "changeguard.github.connection.organizations[1].administrators=bob"
})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class GitHubConnectionTests {
    private static final String ROOT = "/api/v1/organizations/internal-org/integrations/github";
    private static final String CALLBACK = "/api/v1/integrations/github/callback";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private ConnectorIntegrationRepository integrations;
    @Autowired private GitHubLifecycleStore lifecycle;
    @MockitoBean private GitHubProvider provider;

    @BeforeEach
    void prepare() {
        jdbc.sql("""
                TRUNCATE connector.github_installation_states, connector.outbox_events, connector.webhook_receipts,
                         connector.connected_repositories, connector.integrations
                """).update();
        when(provider.installation(anyLong())).thenReturn(installation(99, false));
        when(provider.repositories(123)).thenReturn(List.of(
                new GitHubProvider.GitHubRepository(12345, 99, "changeguard/example", "main"),
                new GitHubProvider.GitHubRepository(67890, 99, "changeguard/other", "develop")));
    }

    @Test
    void authorizedConnectionSelectionAndSignedMergeReachDurableOutbox() throws Exception {
        String state = start();
        String hash = jdbc.sql("SELECT state_hash FROM connector.github_installation_states").query(String.class).single();
        assertThat(hash).hasSize(64).doesNotContain(state);
        String integration = complete(state);
        var owned = integrations.findGitHubIntegration("internal-org", integration).orElseThrow();
        assertThat(owned.githubAccountId()).isEqualTo(99L);
        assertThat(owned.githubAccountLogin()).isEqualTo("changeguard");
        select(integration, "[12345]").andExpect(status().isNoContent());
        merge().andExpect(status().isAccepted());
        assertThat(count("webhook_receipts")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        var payload = json.readTree(jdbc.sql("SELECT payload::text FROM connector.outbox_events").query(String.class).single());
        assertThat(payload.path("organizationId").asString()).isEqualTo("internal-org");
        assertThat(payload.path("source").path("integrationId").asString()).isEqualTo(integration);
        assertThat(jdbc.sql("SELECT consumed_at IS NOT NULL FROM connector.github_installation_states")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void unauthorizedOrganizationAndUserCannotStartOrListConnections() throws Exception {
        mvc.perform(post(ROOT + "/installations").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/installations").with(user("bob")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(ROOT).with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/organizations/org-b/integrations/github/installations")
                .with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        assertThat(count("github_installation_states")).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    void callbackRequiresTheSameAdministratorEvenIfAnotherUserAlsoManagesOrganization() throws Exception {
        String state = start();
        callback(state, "other-admin").andExpect(status().isBadRequest());
        callback(state, "bob").andExpect(status().isForbidden());
        assertThat(count("integrations")).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    void missingTamperedExpiredAndReplayedStatesAreRejected() throws Exception {
        mvc.perform(get(CALLBACK).with(user("alice")).param("installation_id", "123"))
                .andExpect(status().isBadRequest());
        String state = start();
        String tampered = (state.charAt(0) == 'a' ? "b" : "a") + state.substring(1);
        callback(tampered, "alice").andExpect(status().isBadRequest());
        callback("invalid", "alice").andExpect(status().isBadRequest());
        jdbc.sql("UPDATE connector.github_installation_states SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();
        callback(state, "alice").andExpect(status().isBadRequest());
        String valid = start();
        complete(valid);
        callback(valid, "alice").andExpect(status().isBadRequest());
        assertThat(count("integrations")).isEqualTo(1);
        verify(provider, times(1)).installation(123);
    }

    @Test
    void concurrentCallbacksConsumeStateOnce() throws Exception {
        String state = start();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> callback(state, "alice").andReturn().getResponse().getStatus());
            var second = workers.submit(() -> callback(state, "alice").andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(count("integrations")).isEqualTo(1);
    }

    @Test
    void repeatedConnectionKeepsIntegrationIdentityAndSelectedRepositories() throws Exception {
        String first = complete(start());
        select(first, "[12345]").andExpect(status().isNoContent());
        String second = complete(start());
        assertThat(second).isEqualTo(first);
        assertThat(count("integrations")).isEqualTo(1);
        assertThat(integrations.findActiveRepository("internal-org", first, "12345")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "app", "suspended"})
    void callbackRejectsForeignAccountAppAndSuspendedInstallationsWithoutConsumingState(String mismatch) throws Exception {
        String state = start();
        when(provider.installation(123)).thenReturn(new GitHubProvider.Installation(123,
                mismatch.equals("app") ? 8 : 7, mismatch.equals("account") ? 200 : 99,
                "changeguard", "Organization", mismatch.equals("suspended")));
        callback(state, "alice").andExpect(status().isForbidden());
        assertThat(count("integrations")).isZero();
        doReturn(installation(99, false)).when(provider).installation(123);
        complete(state);
    }

    @Test
    void existingInstallationCannotBeReassignedToAnotherOrganization() throws Exception {
        integrations.createGitHubIntegration("org-b", "existing", 123);
        String state = start();
        callback(state, "alice").andExpect(status().isConflict());
        assertThat(integrations.findGitHubIntegration("org-b", "existing")).isPresent();
        assertThat(jdbc.sql("SELECT consumed_at IS NULL FROM connector.github_installation_states")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void selectionUsesProviderMetadataAndRejectsInaccessibleRepositoriesAtomically() throws Exception {
        String integration = complete(start());
        select(integration, "[12345]").andExpect(status().isNoContent());
        select(integration, "[67890,99999]").andExpect(status().isForbidden());
        assertThat(integrations.findActiveRepository("internal-org", integration, "12345")).isPresent();
        assertThat(integrations.findActiveRepository("internal-org", integration, "67890")).isEmpty();
        mvc.perform(put(ROOT + "/" + integration + "/repositories").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repositoryIds\":[67890],\"fullName\":\"attacker/forged\"}"))
                .andExpect(status().isNoContent());
        assertThat(integrations.findActiveRepository("internal-org", integration, "67890").orElseThrow().fullName())
                .isEqualTo("changeguard/other");
        assertThat(integrations.findActiveRepository("internal-org", integration, "12345")).isEmpty();
        select(integration, "[]").andExpect(status().isNoContent());
        assertThat(integrations.findActiveRepository("internal-org", integration, "67890")).isEmpty();
    }

    @Test
    void providerRepositoryFromAnotherAccountCannotBeSelected() throws Exception {
        String integration = complete(start());
        when(provider.repositories(123)).thenReturn(List.of(new GitHubProvider.GitHubRepository(12345, 200, "other/repo", "main")));
        select(integration, "[12345]").andExpect(status().isForbidden());
        assertThat(count("connected_repositories")).isZero();
    }

    @Test
    void repositoryRemovalDuringProviderLookupCannotBeUndoneByStaleSelection() throws Exception {
        String integration = complete(start());
        select(integration, "[12345]").andExpect(status().isNoContent());
        when(provider.repositories(123)).thenAnswer(invocation -> {
            lifecycle.accept(new LifecycleDelivery("installation_repositories", "removed", 123, 99L, List.of("12345")),
                    "concurrent-removal", java.time.Instant.now());
            return List.of(new GitHubProvider.GitHubRepository(12345, 99, "changeguard/example", "main"));
        });
        select(integration, "[12345]").andExpect(status().isConflict());
        assertThat(integrations.findActiveRepository("internal-org", integration, "12345")).isEmpty();
    }

    @Test
    void suspensionDuringCallbackVerificationCannotBeOverwrittenByStaleProviderResponse() throws Exception {
        String integration = complete(start());
        String state = start();
        when(provider.installation(123)).thenAnswer(invocation -> {
            lifecycle.accept(new LifecycleDelivery("installation", "suspend", 123, 99L, List.of()),
                    "concurrent-suspension", java.time.Instant.now());
            return installation(99, false);
        });
        callback(state, "alice").andExpect(status().isConflict());
        assertThat(integrations.findGitHubIntegration("internal-org", integration).orElseThrow().status().name())
                .isEqualTo("DISCONNECTED");
    }

    @Test
    void crossOrganizationReadSelectionAndDisconnectCannotReachIntegration() throws Exception {
        String integration = complete(start());
        String root = "/api/v1/organizations/org-b/integrations/github/" + integration;
        mvc.perform(get(root + "/repositories").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(put(root + "/repositories").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryIds\":[12345]}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(root).with(user("bob")).with(csrf())).andExpect(status().isNotFound());
        assertThat(integrations.findGitHubIntegration("internal-org", integration).orElseThrow().status().name())
                .isEqualTo("ACTIVE");
    }

    @Test
    void disconnectStopsFutureIntakeAndReconnectRequiresNewSelectionWhileRetainingOutbox() throws Exception {
        String integration = complete(start());
        select(integration, "[12345]").andExpect(status().isNoContent());
        merge().andExpect(status().isAccepted());
        mvc.perform(delete(ROOT + "/" + integration).with(user("alice")).with(csrf())).andExpect(status().isNoContent());
        merge().andExpect(status().isForbidden());
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(complete(start())).isEqualTo(integration);
        merge().andExpect(status().isForbidden());
        select(integration, "[12345]").andExpect(status().isNoContent());
        merge().andExpect(status().isAccepted());
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void providerOutageAndDatabaseFailureLeaveCallbackRetryable() throws Exception {
        String state = start();
        when(provider.installation(123)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub API is unavailable"));
        callback(state, "alice").andExpect(status().isServiceUnavailable());
        doReturn(installation(99, false)).when(provider).installation(123);
        jdbc.sql("ALTER TABLE connector.integrations ADD CONSTRAINT reject_connection CHECK (false) NOT VALID").update();
        try {
            callback(state, "alice").andExpect(status().isServiceUnavailable());
            assertThat(count("integrations")).isZero();
            assertThat(jdbc.sql("SELECT consumed_at IS NULL FROM connector.github_installation_states")
                    .query(Boolean.class).single()).isTrue();
        } finally {
            jdbc.sql("ALTER TABLE connector.integrations DROP CONSTRAINT reject_connection").update();
        }
        complete(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[-1]", "[0]", "[null]"})
    void malformedSelectionIsRejectedBeforeProviderCalls(String ids) throws Exception {
        String integration = complete(start());
        select(integration, ids).andExpect(status().isBadRequest());
        verify(provider, times(1)).installation(123);
    }

    private String start() throws Exception {
        String response = mvc.perform(post(ROOT + "/installations").with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = json.readTree(response).path("installationUrl").asString();
        assertThat(url).startsWith("https://github.com/apps/changeguard-test/installations/new?state=");
        return url.substring(url.indexOf("state=") + 6);
    }

    private String complete(String state) throws Exception {
        return json.readTree(callback(state, "alice").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("integrationId").asString();
    }

    private ResultActions callback(String state, String caller) throws Exception {
        return mvc.perform(get(CALLBACK).with(user(caller)).param("state", state).param("installation_id", "123"));
    }

    private ResultActions select(String integration, String ids) throws Exception {
        return mvc.perform(put(ROOT + "/" + integration + "/repositories").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryIds\":" + ids + "}"));
    }

    private ResultActions merge() throws Exception {
        return mvc.perform(post("/api/v1/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request").header("X-GitHub-Delivery", "owned-merge")
                .header("X-Hub-Signature-256", signature(fixture())).content(fixture()));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM connector." + table).query(Integer.class).single();
    }

    private GitHubProvider.Installation installation(long account, boolean suspended) {
        return new GitHubProvider.Installation(123, 7, account, "changeguard", "Organization", suspended);
    }
}
