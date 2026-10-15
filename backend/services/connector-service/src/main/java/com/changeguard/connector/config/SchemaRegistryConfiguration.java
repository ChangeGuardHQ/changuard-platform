package com.changeguard.connector.config;

import java.util.List;
import java.util.Map;

import com.changeguard.events.CodeEventSchemas;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/** Registers the reviewed catalog before application readiness; serializers only resolve it. */
@Configuration(proxyBeanMethods = false)
public class SchemaRegistryConfiguration {

    @Bean
    public SchemaRegistryClient schemaRegistryClient(KafkaProperties properties) {
        Map<String, Object> settings = properties.buildProducerProperties();
        String url = String.valueOf(settings.getOrDefault("schema.registry.url", ""));
        Assert.hasText(url, "Schema Registry URL is required");
        return SchemaRegistryClientFactory.newClient(List.of(url), 1000,
                List.of(new AvroSchemaProvider()), settings, Map.of());
    }

    @Bean
    public RegisteredCodeEventContracts registeredCodeEventContracts(SchemaRegistryClient registry,
            @Value("${changeguard.kafka.topics.code-events}") String topic) throws Exception {
        for (var schema : CodeEventSchemas.all()) {
            String subject = CodeEventSchemas.subject(topic, schema);
            registry.updateCompatibility(subject, CodeEventSchemas.COMPATIBILITY);
            // Registration checks all earlier versions and is idempotent for
            // the exact reviewed schema. Failure prevents producer creation.
            registry.register(subject, new AvroSchema(schema), true);
        }
        return new RegisteredCodeEventContracts();
    }

    public record RegisteredCodeEventContracts() {
    }
}
