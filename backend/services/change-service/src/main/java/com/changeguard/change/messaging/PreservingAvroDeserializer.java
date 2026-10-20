package com.changeguard.change.messaging;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.kafka.common.header.Headers;

/** Captures bytes before decoding, so DLT publication never needs a new Registry subject. */
public class PreservingAvroDeserializer extends KafkaAvroDeserializer {
    @Override
    public Object deserialize(String topic, Headers headers, byte[] data) {
        OriginalBytes.capture(headers, OriginalBytes.VALUE, data);
        return super.deserialize(topic, headers, data);
    }
}
