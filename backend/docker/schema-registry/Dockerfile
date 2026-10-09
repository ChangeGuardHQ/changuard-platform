# syntax=mirror.gcr.io/docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# Assemble the core server from official artifacts, using the platform's patched BOMs.
FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /workspace
COPY services/connector-service/.mvn .mvn
COPY services/connector-service/mvnw pom.xml ./
COPY event-contracts/pom.xml event-contracts/pom.xml
COPY services/connector-service/pom.xml services/connector-service/pom.xml
COPY docker/schema-registry/pom.xml docker/schema-registry/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw --batch-mode --no-transfer-progress -pl docker/schema-registry -am -DskipTests package

FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jre-alpine-3.24@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN addgroup -S -g 10001 changeguard \
    && adduser -S -D -H -u 10001 -G changeguard changeguard
WORKDIR /opt/schema-registry
COPY --from=build --chown=10001:10001 /workspace/docker/schema-registry/target/dependency lib
COPY --chown=10001:10001 docker/schema-registry/entrypoint.sh docker/schema-registry/log4j2.xml ./
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=5s --start-period=30s --retries=20 \
    CMD wget -q -O /dev/null http://localhost:8081/subjects || exit 1
ENTRYPOINT ["sh", "/opt/schema-registry/entrypoint.sh"]
