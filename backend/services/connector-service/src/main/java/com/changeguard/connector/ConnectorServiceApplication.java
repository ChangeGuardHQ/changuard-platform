package com.changeguard.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap class for the Connector Service application. This class initializes
 * the Spring Boot application context for the connector service.
 */
@SpringBootApplication
public class ConnectorServiceApplication {

    /**
     * Starts the Connector Service application.
     *
     * @param args command-line arguments passed to the Spring Boot application
     */
    public static void main(String[] args) {
        SpringApplication.run(ConnectorServiceApplication.class, args);
    }

}
