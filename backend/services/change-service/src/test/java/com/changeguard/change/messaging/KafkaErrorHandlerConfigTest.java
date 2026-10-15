package com.changeguard.change.messaging;

import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KafkaErrorHandlerConfigTest {

    @Test
    void failureStopsTheContainerWithoutCommittingOrDiscardingTheRecord() {
        var handler = new KafkaErrorHandlerConfig().kafkaErrorHandler();
        var container = mock(MessageListenerContainer.class);
        var consumer = mock(Consumer.class);
        var record = new ConsumerRecord<>("probe", 0, 7L, "key", "payload");

        assertThatThrownBy(() -> handler.handleRemaining(new IllegalStateException("processing failed"),
                List.of(record), consumer, container)).isInstanceOf(KafkaException.class);

        verify(container, timeout(3000)).stopAbnormally(any(Runnable.class));
        verifyNoInteractions(consumer);
    }
}
