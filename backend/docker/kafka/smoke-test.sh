#!/bin/sh
set -eu

# Run from the repository root; optional arguments are passed to Docker Compose.
smoke_topic="changeguard-ci-smoke-$(date +%s)-$$"
smoke_payload='{"source":"changeguard-ci","status":"ok"}'

docker compose "$@" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 --create --topic "$smoke_topic" \
    --partitions 1 --replication-factor 1

printf '%s\n' "$smoke_payload" | docker compose "$@" exec -T kafka \
    /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka:9092 --topic "$smoke_topic" \
    --command-property acks=all --command-property max.block.ms=10000 \
    --command-property delivery.timeout.ms=30000 --command-property request.timeout.ms=10000

received_payload=$(docker compose "$@" exec -T kafka \
    /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 --topic "$smoke_topic" \
    --from-beginning --max-messages 1 --timeout-ms 10000)

if [ "$received_payload" != "$smoke_payload" ]; then
    echo "Kafka smoke test failed: consumed payload did not match the produced payload." >&2
    exit 1
fi

docker compose "$@" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 --delete --topic "$smoke_topic"
echo "Kafka message round trip passed."
