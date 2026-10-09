package com.changeguard.connector.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;

/**
 * Configures the Kafka producer infrastructure used by the connector service.
 * This class exposes the Spring-managed producer factory and template used to
 * publish events to Kafka topics.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaProducerConfig {

    /**
     * Creates the producer factory using the application configuration for
     * Kafka.
     *
     * @param properties the auto-configured Kafka properties from Spring Boot
     * @return a configured producer factory for String keys and Object payloads
     */
    @Bean
    @DependsOn("registeredCodeEventContracts")
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
    }

    /**
     * Creates the Kafka template used by application components to send
     * messages.
     *
     * @param producerFactory the configured producer factory
     * @return a template for publishing messages to Kafka topics
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        LoggingProducerListener<String, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        return template;
    }
}
