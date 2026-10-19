package com.changeguard.change;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(PostgresTestConfiguration.class)
class ChangeServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void postgresFlywayAndJpaStartWithTheDomainSchema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'change' "
                        + "AND table_name IN ('change_records', 'processed_events')", Integer.class)).isEqualTo(2);
        assertThat(listenerRegistry.getListenerContainers()).hasSize(1);
        assertThat(listenerRegistry.getListenerContainer("code-change-events").isRunning()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesArePublicWithoutExposingDetails(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void authenticatedReadinessIncludesTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")
                        .with(httpBasic("infrastructure-test", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/prometheus", "/api/v1/changes"})
    void otherEndpointsRequireAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedPrometheusScrapeReturnsMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(httpBasic("infrastructure-test", "test-password")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

}
