package com.changeguard.change.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.support.KafkaHeaders;

/** Explicit, single-offset replay; no consumer-group commits and no payloads printed to the terminal. */
public final class DeadLetterReplay {
    private DeadLetterReplay() { }

    public static void main(String[] args) throws Exception {
        var options = options(args);
        var settings = new HashMap<String, Object>();
        if (options.containsKey("client-properties")) {
            var supplied = new Properties();
            try (var input = Files.newInputStream(Path.of(options.get("client-properties")))) { supplied.load(input); }
            supplied.forEach((key, value) -> settings.put(key.toString(), value));
        }
        settings.put("bootstrap.servers", required(options, "bootstrap-servers"));
        settings.put("key.deserializer", ByteArrayDeserializer.class);
        settings.put("value.deserializer", ByteArrayDeserializer.class);
        settings.put("enable.auto.commit", false);
        settings.put("max.poll.records", 1);
        settings.put("isolation.level", "read_committed");
        int partition = Integer.parseInt(required(options, "partition"));
        long offset = Long.parseLong(required(options, "offset"));
        if (partition < 0 || offset < 0) throw new IllegalArgumentException("Partition and offset must be nonnegative");
        String dlt = required(options, "dead-letter-topic");
        String source = required(options, "source-topic");
        if (dlt.equals(source)) throw new IllegalArgumentException("Source and DLT must differ");
        var target = new TopicPartition(dlt, partition);
        try (var consumer = new KafkaConsumer<byte[], byte[]>(settings)) {
            consumer.assign(List.of(target));
            consumer.seek(target, offset);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.offset() != offset) throw new IllegalArgumentException("Requested DLT offset is unavailable");
                    var replay = replayRecord(record, source);
                    System.out.printf("%s[%d]@%d -> %s[%d] (%s)%n", dlt, partition, offset,
                            replay.topic(), replay.partition(), options.containsKey("execute") ? "execute" : "dry run");
                    if (options.containsKey("execute")) {
                        settings.put("key.serializer", ByteArraySerializer.class);
                        settings.put("value.serializer", ByteArraySerializer.class);
                        settings.put("acks", "all");
                        settings.put("enable.idempotence", true);
                        settings.put("max.block.ms", 10000);
                        settings.put("delivery.timeout.ms", 10000);
                        settings.put("request.timeout.ms", 5000);
                        try (var producer = new KafkaProducer<byte[], byte[]>(settings)) {
                            producer.send(replay).get(15, TimeUnit.SECONDS);
                        }
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("Requested DLT offset was not found within 15 seconds");
        }
    }

    static ProducerRecord<byte[], byte[]> replayRecord(ConsumerRecord<byte[], byte[]> record, String source) {
        var topic = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        var partition = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION);
        if (topic == null || !source.equals(new String(topic.value(), StandardCharsets.UTF_8))
                || partition == null || partition.value().length != Integer.BYTES)
            throw new IllegalArgumentException("DLT provenance does not match the explicitly selected source topic");
        int sourcePartition = ByteBuffer.wrap(partition.value()).getInt();
        if (sourcePartition < 0) throw new IllegalArgumentException("Invalid source partition");
        // Publish fresh recovery headers on a subsequent failure, retaining only application headers.
        var headers = new RecordHeaders();
        record.headers().forEach(header -> {
            if (!header.key().startsWith("kafka_dlt-") && !header.key().startsWith("springDeserializerException")
                    && !header.key().startsWith("changeguard.original.")) headers.add(header);
        });
        return new ProducerRecord<>(source, sourcePartition, record.key(), record.value(), headers);
    }

    private static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        for (String argument : args) {
            if (!argument.startsWith("--")) throw new IllegalArgumentException("Arguments must use --name=value");
            var pair = argument.substring(2).split("=", 2);
            if (!List.of("bootstrap-servers", "dead-letter-topic", "source-topic", "partition", "offset",
                    "client-properties", "execute").contains(pair[0]) || options.containsKey(pair[0]))
                throw new IllegalArgumentException("Unknown or duplicate option: " + pair[0]);
            if (pair[0].equals("execute") && pair.length != 1)
                throw new IllegalArgumentException("Execution requires the explicit --execute flag");
            options.put(pair[0], pair.length == 1 ? "" : pair[1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }
}
