package com.changeguard.connector.config;

import java.util.Map;

import com.changeguard.events.CodeEventSchemas;
import com.changeguard.events.code.PullRequestMerged;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class SchemaRegistryConfigurationTests {
    @Test
    void repeatedStartupRegistersTheReviewedCatalogWithoutCreatingNewVersions() throws Exception {
        var registry = new MockSchemaRegistryClient();
        var config = new SchemaRegistryConfiguration();
        config.registeredCodeEventContracts(registry, "changeguard.code-events");
        config.registeredCodeEventContracts(registry, "changeguard.code-events");
        for (var schema : CodeEventSchemas.all()) {
            var subject = CodeEventSchemas.subject("changeguard.code-events", schema);
            assertThat(registry.getCompatibility(subject)).isEqualTo("BACKWARD_TRANSITIVE");
            assertThat(registry.getAllVersions(subject)).containsExactly(1);
        }
    }

    @Test
    void registrationFailuresPreventCreationOfTheReadyCatalog() throws Exception {
        var registry = spy(new MockSchemaRegistryClient());
        doThrow(new java.io.IOException("Registry unavailable"))
                .when(registry).register(anyString(), any(AvroSchema.class), anyBoolean());
        assertThatThrownBy(() -> new SchemaRegistryConfiguration()
                .registeredCodeEventContracts(registry, "changeguard.code-events"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void publishingCannotAutoRegisterAnUnreviewedSubject() throws Exception {
        var registry = spy(new MockSchemaRegistryClient());
        try (var serializer = new KafkaAvroSerializer(registry)) {
            serializer.configure(Map.of("schema.registry.url", "mock://missing-contract-test",
                    "auto.register.schemas", false, "normalize.schemas", true,
                    "value.subject.name.strategy", "io.confluent.kafka.serializers.subject.TopicRecordNameStrategy"), false);
            // Schema lookup occurs before writing the record's field values.
            assertThatThrownBy(() -> serializer.serialize("unreviewed.code-events", new PullRequestMerged()))
                    .isInstanceOf(SerializationException.class);
            verify(registry, never()).register(anyString(), any(io.confluent.kafka.schemaregistry.ParsedSchema.class),
                    anyBoolean());
        }
    }
}
