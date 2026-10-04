package com.changeguard.connector.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigTests.TestController.class)
@Import({SecurityConfig.class, SecurityConfigTests.TestController.class, SecurityConfigTests.TestUsers.class})
class SecurityConfigTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void webhookPostReachesControllerWithoutLoginOrCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/github/webhook").content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthChecksArePublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/prometheus", "/api/v1/integrations/github",
            "/api/v1/integrations/github/webhook", "/api/v1/private", "/login"})
    void otherGetRequestsRequireAuthentication(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic ")))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void webhookExceptionDoesNotAllowOtherMethodsOrChildPaths() throws Exception {
        mockMvc.perform(delete("/api/v1/integrations/github/webhook").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/integrations/github/webhook/extra").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/integrations/github").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basicAuthenticationAllowsProtectedRequestsWithoutCreatingSession() throws Exception {
        mockMvc.perform(get("/api/v1/private").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void authenticatedWritesOutsideWebhookStillRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/private").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/private").with(httpBasic("test-user", "test-password")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void errorDispatchIsAllowed() throws Exception {
        mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestUsers {

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(User.withUsername("test-user")
                    .password("{noop}test-password").roles("USER").build());
        }
    }

    @TestComponent
    @RestController
    static class TestController {

        @PostMapping("/api/v1/integrations/github/webhook")
        @ResponseStatus(HttpStatus.ACCEPTED)
        void webhook() {
        }

        @GetMapping({"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                "/api/v1/private", "/error"})
        String read() {
            return "ok";
        }

        @PostMapping("/api/v1/private")
        String write() {
            return "ok";
        }
    }
}
