package com.changeguard.events;

import java.io.IOException;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeEventContractTests {
    static List<Schema> schemas() {
        return CodeEventSchemas.all();
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void readsTheReleasedV1AndRejectsARequiredAdditionWithoutDefault(Schema current) throws IOException {
        var parser = new Schema.Parser();
        for (String common : List.of("EventSource", "EventActor", "EventCorrelation")) {
            try (var stream = getClass().getResourceAsStream("/released/v1/common/" + common + ".avsc")) {
                parser.parse(stream);
            }
        }
        Schema released;
        try (var stream = getClass().getResourceAsStream("/released/v1/" + current.getName() + ".avsc")) {
            released = parser.parse(stream);
        }
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(current, released).getType())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
        // Record schemas cannot be mutated after construction; create a reader
        // with an extra required field, preserving the actual contract fields.
        var fields = current.getFields().stream()
                .map(field -> new Schema.Field(field.name(), field.schema(), field.doc(), field.defaultVal()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        fields.add(new Schema.Field("requiredNewField", Schema.create(Schema.Type.STRING), null, null));
        var incompatible = Schema.createRecord(current.getName(), null, current.getNamespace(), false);
        incompatible.setFields(fields);
        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(incompatible, released).getType())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.INCOMPATIBLE);
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void envelopeAndRepresentativePayloadRoundTripWithNullableFields(Schema schema) throws IOException {
        var event = new GenericData.Record(schema);
        event.put("eventId", "c5b6f5af-ec5e-4a51-b18c-ef7386b8d5d8");
        event.put("eventType", schema.getName());
        event.put("eventVersion", 1);
        event.put("occurredAt", 1791392400123456L);
        event.put("receivedAt", 1791392402123456L);
        event.put("organizationId", "org-1");
        var source = new GenericData.Record(schema.getField("source").schema());
        source.put("provider", "github");
        source.put("integrationId", "integration-1");
        event.put("source", source);
        event.put("actor", null);
        event.put("correlation", null);
        var payload = new GenericData.Record(schema.getField("payload").schema());
        for (var field : payload.getSchema().getFields()) {
            payload.put(field.name(), switch (field.name()) {
                case "deliveryId" -> "delivery-1";
                case "repositoryId" -> "9007199254740993";
                case "repositoryFullName" -> "acme/café-🌱";
                case "pullRequestNumber" -> 42;
                case "commitSha", "headCommitSha" -> "cccccccccccccccccccccccccccccccccccccccc";
                case "sourceBranch" -> "feature/checkout";
                case "targetBranch" -> "main";
                default -> null;
            });
        }
        event.put("payload", payload);
        CodeEventSchemas.validate(event);
        assertThat(GenericData.get().validate(schema, event)).isTrue();
        var bytes = new java.io.ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(bytes, null);
        new GenericDatumWriter<GenericRecord>(schema).write(event, encoder);
        encoder.flush();
        var decoded = new GenericDatumReader<GenericRecord>(schema).read(null,
                DecoderFactory.get().binaryDecoder(bytes.toByteArray(), null));
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.get("actor")).isNull();
        assertThat(((GenericRecord) decoded.get("payload")).get("repositoryId").toString())
                .isEqualTo("9007199254740993");

        var canonicalEnvelope = CodeEventSchemas.all().getFirst().getFields().stream()
                .filter(field -> !List.of("payload", "eventType").contains(field.name()))
                .map(field -> field.name() + ":" + field.schema()).toList();
        assertThat(schema.getFields().stream()
                .filter(field -> !List.of("payload", "eventType").contains(field.name()))
                .map(field -> field.name() + ":" + field.schema()).toList()).isEqualTo(canonicalEnvelope);
        event.put("eventType", "UnknownEvent");
        assertThatThrownBy(() -> CodeEventSchemas.validate(event)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event discriminator must match its Avro record");
        event.put("eventType", schema.getName());
        event.put("eventVersion", 2);
        assertThatThrownBy(() -> CodeEventSchemas.validate(event)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported code event version");
    }
}
