package com.changeguard.change.messaging;

import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Missing snapshots must fail closed rather than quarantine a different payload. */
class KafkaErrorHandlerConfigTest {
    @Test
    void quarantineRetainsExactBytesAndRemovesLocalSnapshots() {
        var record = new ConsumerRecord<String, Object>("source", 0, 7, "decoded", new Object());
        byte[] key = {(byte) 0xff};
        byte[] value = {0, 1, 2, 3};
        OriginalBytes.capture(record.headers(), OriginalBytes.KEY, key);
        OriginalBytes.capture(record.headers(), OriginalBytes.VALUE, value);
        var recoverer = new RawDeadLetterRecoverer(mock(KafkaTemplate.class), "dlt");
        var quarantined = recoverer.createProducerRecord(record, new TopicPartition("dlt", -1),
                new RecordHeaders(record.headers()), null, null);
        assertThat((byte[]) quarantined.key()).containsExactly(key);
        assertThat((byte[]) quarantined.value()).containsExactly(value);
        assertThat(quarantined.headers().lastHeader(OriginalBytes.VALUE)).isNull();
        assertThat(quarantined.headers().lastHeader(OriginalBytes.KEY)).isNull();
    }

    @Test
    void missingSnapshotAndUnboundedRetryConfigurationAreRejected() {
        var recoverer = new RawDeadLetterRecoverer(mock(KafkaTemplate.class), "dlt");
        assertThatThrownBy(() -> recoverer.createProducerRecord(
                new ConsumerRecord<>("source", 0, 7, "key", "value"), new TopicPartition("dlt", -1),
                new RecordHeaders(), null, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecoveryProperties("dlt", Duration.ofSeconds(11), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
