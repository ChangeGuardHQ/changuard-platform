package com.changeguard.events;

import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.util.ClassSecurityValidator;

import com.changeguard.events.code.CommitCreated;
import com.changeguard.events.code.PullRequestMerged;
import com.changeguard.events.code.PullRequestOpened;

/**
 * Catalog of the Avro schemas published for code-related events.
 *
 * <p>
 * The schemas are shared by event producers and consumers so that each event
 * contract is represented by a single generated Avro class. The catalog is
 * immutable from callers' perspective: callers can retrieve the schemas with
 * {@link #all()} but cannot modify the catalog itself.</p>
 */
public final class CodeEventSchemas {

    /**
     * Compatibility level used when validating code-event schema changes.
     */
    public static final String COMPATIBILITY = "BACKWARD_TRANSITIVE";

    private static boolean configured;

    static {
        trustGeneratedClasses();
    }

    private CodeEventSchemas() {
    }

    /**
     * Returns the generated Avro schemas for all supported code-event records.
     *
     * @return an immutable list containing the pull-request opened,
     * pull-request merged, and commit-created schemas
     */
    public static List<Schema> all() {
        return List.of(PullRequestOpened.getClassSchema(), PullRequestMerged.getClassSchema(),
                CommitCreated.getClassSchema());
    }

    /**
     * Registers the generated event classes with Avro's class-security
     * validator.
     *
     * <p>
     * Avro 1.12.2 rejects specific record classes unless they are explicitly
     * permitted. This method adds the generated code-event records and their
     * supporting payload and metadata classes to the global allowlist, once.
     */
    public static synchronized void trustGeneratedClasses() {
        if (configured) {
            return;
        }
        var allowed = ClassSecurityValidator.builder()
                .add(PullRequestOpened.class).add(PullRequestMerged.class).add(CommitCreated.class)
                .add(com.changeguard.events.code.PullRequestOpenedPayload.class)
                .add(com.changeguard.events.code.PullRequestMergedPayload.class)
                .add(com.changeguard.events.code.CommitCreatedPayload.class)
                .add(com.changeguard.events.common.EventSource.class)
                .add(com.changeguard.events.common.EventActor.class)
                .add(com.changeguard.events.common.ActorType.class)
                .add(com.changeguard.events.common.EventCorrelation.class).build();
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(), allowed));
        configured = true;
    }

    /**
     * Builds a subject name for an event record on an ordered topic.
     *
     * <p>
     * The topic name is retained as the subject prefix and the Avro schema full
     * name is appended so that multiple lifecycle records can share one topic
     * without colliding subject names.</p>
     *
     * @param topic the ordered event topic
     * @param schema the schema whose full name identifies the record type
     * @return the topic and schema-qualified subject name
     * @throws IllegalArgumentException if the topic is null or blank
     */
    public static String subject(String topic, Schema schema) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic is required");
        }
        return topic + "-" + schema.getFullName();
    }

    /** Semantic invariants Avro's structural type validation cannot express. */
    public static void validate(IndexedRecord event) {
        if (event == null || all().stream().noneMatch(schema -> schema.getFullName().equals(event.getSchema().getFullName()))) {
            throw new IllegalArgumentException("Unsupported code event record");
        }
        if (!event.getSchema().getName().equals(String.valueOf(field(event, "eventType")))) {
            throw new IllegalArgumentException("Event discriminator must match its Avro record");
        }
        if (!Integer.valueOf(1).equals(field(event, "eventVersion"))) {
            throw new IllegalArgumentException("Unsupported code event version");
        }
        for (String name : List.of("eventId", "organizationId")) requireText(field(event, name), name);
        if (field(event, "occurredAt") == null || field(event, "receivedAt") == null) {
            throw new IllegalArgumentException("Occurrence and receipt timestamps are required");
        }
        var source = requireRecord(field(event, "source"), "source");
        requireText(field(source, "provider"), "provider");
        requireText(field(source, "integrationId"), "integrationId");
        if (field(event, "actor") != null) {
            requireText(field(requireRecord(field(event, "actor"), "actor"), "id"), "actor.id");
        }
        var payload = requireRecord(field(event, "payload"), "payload");
        for (String name : List.of("deliveryId", "repositoryId", "repositoryFullName")) {
            requireText(field(payload, name), name);
        }
        if (event.getSchema().getName().equals("CommitCreated")) {
            requireText(field(payload, "commitSha"), "commitSha");
        } else {
            Object number = field(payload, "pullRequestNumber");
            if (!(number instanceof Integer value) || value <= 0) {
                throw new IllegalArgumentException("Pull request number must be positive");
            }
            requireText(field(payload, event.getSchema().getName().equals("PullRequestMerged")
                    ? "commitSha" : "headCommitSha"), "commit SHA");
            requireText(field(payload, "sourceBranch"), "sourceBranch");
            requireText(field(payload, "targetBranch"), "targetBranch");
        }
    }

    private static Object field(IndexedRecord record, String name) {
        return record.get(record.getSchema().getField(name).pos());
    }

    private static IndexedRecord requireRecord(Object value, String name) {
        if (!(value instanceof IndexedRecord record)) throw new IllegalArgumentException(name + " is required");
        return record;
    }

    private static void requireText(Object value, String name) {
        if (!(value instanceof CharSequence text) || text.toString().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
