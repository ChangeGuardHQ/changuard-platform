package com.changeguard.change.messaging;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Even an invalid UTF-8 partition key must survive quarantine without a lossy round trip. */
public class PreservingStringDeserializer extends StringDeserializer {
    @Override
    public String deserialize(String topic, Headers headers, byte[] data) {
        OriginalBytes.capture(headers, OriginalBytes.KEY, data);
        return super.deserialize(topic, headers, data);
    }
}
