package com.changeguard.change.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedReadsRequireAuthenticationAndDoNotCreateSessions() throws Exception {
        mockMvc.perform(get("/infrastructure-probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/infrastructure-probe").with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void authenticatedWritesStillRequireCsrf() throws Exception {
        mockMvc.perform(post("/infrastructure-probe").with(user("test-user")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/infrastructure-probe").with(user("test-user")).with(csrf()))
                .andExpect(status().isOk());
    }

    @TestComponent
    @RestController
    static class ProbeController {

        @GetMapping("/infrastructure-probe")
        String read() {
            return "ok";
        }

        @PostMapping("/infrastructure-probe")
        void write() {
        }
    }
}
