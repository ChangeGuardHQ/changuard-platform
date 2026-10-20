package com.changeguard.change.messaging;

import org.apache.kafka.common.header.Headers;

/** Local-only snapshots let quarantine preserve the original Registry framing, including malformed bytes. */
final class OriginalBytes {
    static final String KEY = "changeguard.original.key";
    static final String VALUE = "changeguard.original.value";

    private OriginalBytes() { }

    static void capture(Headers headers, String name, byte[] bytes) {
        // Never trust a producer-supplied snapshot; replace it before decoding this record.
        headers.remove(name);
        if (bytes != null) headers.add(name, bytes.clone());
    }
}
