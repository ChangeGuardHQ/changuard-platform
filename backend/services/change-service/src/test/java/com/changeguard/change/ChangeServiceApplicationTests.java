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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
@Testcontainers
class ChangeServiceApplicationTests {

    @Container
    @ServiceConnection
    // Use the same schema and configurable hardened test image as the integrated runtime.
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("changeguard.test.postgres-image",
                    "mirror.gcr.io/library/postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"))
            .asCompatibleSubstituteFor("postgres")).withUrlParam("currentSchema", "change");

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
    void postgresFlywayAndJpaStartWithOnlyTheInfrastructureTestSchema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(flyway.info().current().getScript()).isEqualTo("V1__create_infrastructure_probe.sql");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM infrastructure_probe", Integer.class)).isEqualTo(1);
        assertThat(listenerRegistry.getListenerContainers()).isEmpty();
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
