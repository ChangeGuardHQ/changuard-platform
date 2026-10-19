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
        // Unsupported facts and tombstones stop consumption rather than silently losing future lifecycle data.
        if (!(record.value() instanceof PullRequestMerged event)) {
            throw new EventProcessingException("This projection supports only PullRequestMerged V1 records");
        }
        var merge = mapper.fromAvro(event);
        if (!merge.repositoryId().equals(record.key())) {
            throw new EventProcessingException("Code event partition key must match its repository identity");
        }
        service.ingest(merge);
    }
}
