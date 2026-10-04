# syntax=docker/dockerfile:1.27.1
FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24 AS jackson-patches

WORKDIR /patches
ENV JACKSON_VERSION=2.21.7
COPY SHA256SUMS ./
RUN set -eu; \
    while read -r checksum jar; do \
        artifact="${jar%-${JACKSON_VERSION}.jar}"; \
        case "$artifact" in \
            jackson-core|jackson-databind) module_group=core ;; \
            jackson-dataformat-*) module_group=dataformat ;; \
            jackson-datatype-*) module_group=datatype ;; \
            jackson-jakarta-rs-*) module_group=jakarta/rs ;; \
            jackson-module-*) module_group=module ;; \
            *) echo "Unexpected Jackson artifact: $artifact" >&2; exit 1 ;; \
        esac; \
        wget -q -T 30 -O "$jar" \
            "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/${module_group}/${artifact}/${JACKSON_VERSION}/${jar}"; \
        printf '%s  %s\n' "$checksum" "$jar" | sha256sum -c -; \
    done < SHA256SUMS

FROM apache/kafka:4.2.2@sha256:1213eb3943d551e5ed1fca7a4e109001cee35770b66a02a0c37a8964efe09b69 AS runtime

LABEL org.opencontainers.image.title="ChangeGuard Kafka" \
      org.opencontainers.image.version="4.2.2-security.1"
USER root
# Replace the vulnerable package and Jackson modules, retaining annotations 2.21
# as specified by the Jackson 2.21.7 BOM. Vendor CDS archives use the old JARs.
RUN apk add --no-cache libexpat=2.8.5-r0 \
    && rm -f /opt/kafka/libs/jackson-*-2.21.6.jar \
        /opt/kafka/kafka.jsa /opt/kafka/storage.jsa \
    && sed -i \
        -e 's| -XX:SharedArchiveFile=/opt/kafka/storage.jsa||g' \
        -e 's| -XX:SharedArchiveFile=/opt/kafka/kafka.jsa||g' \
        /etc/kafka/docker/launch \
    && ! grep -q 'SharedArchiveFile=' /etc/kafka/docker/launch
COPY --from=jackson-patches --chown=appuser:appuser /patches/*.jar /opt/kafka/libs/

USER appuser
HEALTHCHECK --interval=15s --timeout=10s --start-period=30s --retries=5 \
    CMD /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null || exit 1
