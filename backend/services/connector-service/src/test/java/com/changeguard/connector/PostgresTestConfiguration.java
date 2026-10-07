package com.changeguard.connector;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL for full-context tests; Spring owns container startup and shutdown. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // CI uses the locally built hardened runtime image. The pinned upstream
        // default keeps isolated schema tests runnable with Docker alone.
        // Testcontainers expects a repository followed by either a tag or a digest, not both.
        return new PostgreSQLContainer(DockerImageName.parse(
                System.getProperty("changeguard.test.postgres-image",
                        "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"))
                .asCompatibleSubstituteFor("postgres"));
    }
}
