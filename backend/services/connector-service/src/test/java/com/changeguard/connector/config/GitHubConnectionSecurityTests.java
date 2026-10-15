package com.changeguard.connector.config;

import com.changeguard.connector.api.GitHubConnectionController;
import com.changeguard.connector.github.connection.GitHubConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the GitHub connection endpoints enforce the same security posture as
 * production: real cookie-based CSRF tokens are issued for authenticated
 * requests, and the callback/CSRF endpoints require authentication instead of
 * creating a server-side session.
 *
 * <p>
 * These tests intentionally use real cookies and Basic authentication because
 * the Spring Security {@code csrf()} helper would replace the production token
 * repository and mask integration issues.
 */
@WebMvcTest(GitHubConnectionController.class)
@Import({SecurityConfig.class, SecurityConfigTests.TestUsers.class})
class GitHubConnectionSecurityTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private GitHubConnectionService connections;

    /**
     * Confirms the cookie-based CSRF flow allows an authenticated write without
     * creating a HttpSession, which matches the stateless security model used
     * by the app.
     */
    @Test
    void cookieCsrfRoundTripSupportsAuthenticatedWritesWithoutCreatingSession() throws Exception {
        String path = "/api/v1/organizations/org-a/integrations/github/installations";
        mvc.perform(post(path).with(httpBasic("test-user", "test-password"))).andExpect(status().isForbidden());
        var token = mvc.perform(get("/api/v1/integrations/github/csrf").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andReturn();
        var details = json.readTree(token.getResponse().getContentAsString());
        assertThat(token.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(token.getResponse().getCookie("XSRF-TOKEN").isHttpOnly()).isTrue();
        mvc.perform(post(path).with(httpBasic("test-user", "test-password"))
                .cookie(token.getResponse().getCookies())
                .header(details.path("headerName").asString(), details.path("token").asString()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
        assertThat(token.getRequest().getSession(false)).isNull();
    }

    @Test
    void callbackAndCsrfEndpointRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/integrations/github/callback").param("state", "untrusted")
                .param("installation_id", "123")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/integrations/github/csrf")).andExpect(status().isUnauthorized());
    }
}
