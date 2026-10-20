package com.changeguard.change.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/** Quarantines exact source bytes; a failed broker acknowledgment throws and retains the source offset. */
public class RawDeadLetterRecoverer extends DeadLetterPublishingRecoverer {
    public RawDeadLetterRecoverer(KafkaOperations<byte[], byte[]> template, String topic) {
        // Let the DLT choose its partition; it need not mirror the source's partition count.
        super(template, (record, error) -> new TopicPartition(topic, -1));
        setFailIfSendResultIsError(true);
        setAppendOriginalHeaders(false);
        setLogRecoveryRecord(false);
        excludeHeader(HeaderNames.HeadersToAdd.EX_STACKTRACE, HeaderNames.HeadersToAdd.EX_MSG);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
            TopicPartition destination, Headers headers, byte[] key, byte[] value) {
        byte[] originalKey = bytes(record, OriginalBytes.KEY, key);
        byte[] originalValue = bytes(record, OriginalBytes.VALUE, value);
        // Snapshots are internal decoder state, not headers to publish or replay.
        headers.remove(OriginalBytes.KEY).remove(OriginalBytes.VALUE);
        return new ProducerRecord<>(destination.topic(), null, originalKey, originalValue, headers);
    }

    private byte[] bytes(ConsumerRecord<?, ?> record, String name, byte[] failedBytes) {
        var snapshot = record.headers().lastHeader(name);
        if (snapshot != null) return snapshot.value();
        if (failedBytes != null) return failedBytes;
        Object decoded = name.equals(OriginalBytes.KEY) ? record.key() : record.value();
        if (decoded == null) return null; // Tombstones intentionally retain their null value.
        throw new IllegalStateException("Missing original record bytes; source offset must remain uncommitted");
    }
}
