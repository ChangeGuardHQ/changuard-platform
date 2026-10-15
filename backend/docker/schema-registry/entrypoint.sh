#!/bin/sh
set -eu
: "${SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS:?Kafka bootstrap servers are required}"
cat > /tmp/changeguard-schema-registry.properties <<EOF
host.name=${SCHEMA_REGISTRY_HOST_NAME:-schema-registry}
listeners=${SCHEMA_REGISTRY_LISTENERS:-http://0.0.0.0:8081}
kafkastore.bootstrap.servers=${SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS}
kafkastore.topic.replication.factor=${SCHEMA_REGISTRY_KAFKASTORE_TOPIC_REPLICATION_FACTOR:-1}
schema.compatibility.level=BACKWARD_TRANSITIVE
EOF
exec java -Xms128m -Xmx256m -Dlog4j.configurationFile=/opt/schema-registry/log4j2.xml \
    -cp '/opt/schema-registry/lib/*' io.confluent.kafka.schemaregistry.rest.SchemaRegistryMain \
    /tmp/changeguard-schema-registry.properties
