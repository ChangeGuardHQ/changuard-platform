package com.changeguard.change;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Reuse the production migrations against an isolated, disposable PostgreSQL database. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(System.getProperty("changeguard.test.postgres-image",
                "changuard-postgres:18.6-security.1")).asCompatibleSubstituteFor("postgres"))
                .withUrlParam("currentSchema", "change");
    }
}
