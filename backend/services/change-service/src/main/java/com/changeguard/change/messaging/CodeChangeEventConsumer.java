package com.changeguard.change.messaging;

import com.changeguard.change.change.ChangeApplicationService;
import com.changeguard.change.change.ChangeMapper;
import com.changeguard.change.exception.EventProcessingException;
import com.changeguard.events.code.PullRequestMerged;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Record acknowledgment follows the application service's committed database transaction. */
@Component
public class CodeChangeEventConsumer {
    private final ChangeApplicationService service;
    private final ChangeMapper mapper;

    public CodeChangeEventConsumer(ChangeApplicationService service, ChangeMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(id = "code-change-events", topics = "${changeguard.kafka.topics.code-events}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, Object> record) {
        // Dispatch only reviewed specific contracts; all validation errors reach durable quarantine.
        if (record.value() instanceof PullRequestMerged event) {
            var merge = mapper.fromAvro(event);
            requireRepositoryKey(record, merge.repositoryId());
            service.ingest(merge);
        } else if (record.value() instanceof com.changeguard.events.code.PullRequestOpened
                || record.value() instanceof com.changeguard.events.code.CommitCreated) {
            var fact = mapper.fromLifecycle((org.apache.avro.generic.IndexedRecord) record.value());
            requireRepositoryKey(record, fact.repositoryId());
            service.ingestLifecycle(fact);
        } else {
            throw new EventProcessingException("Unsupported code event contract or tombstone");
        }
    }

    private void requireRepositoryKey(ConsumerRecord<String, Object> record, String repository) {
        if (!repository.equals(record.key()))
            throw new EventProcessingException("Code event partition key must match its repository identity");
    }
}
