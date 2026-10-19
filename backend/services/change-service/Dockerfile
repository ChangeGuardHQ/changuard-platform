# syntax=mirror.gcr.io/docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# Build from the backend context so the reactor parent and all module POMs are available.
FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build

WORKDIR /workspace
COPY services/change-service/.mvn .mvn
COPY services/change-service/mvnw pom.xml ./
RUN chmod +x mvnw
# Generate and package the shared Avro classes as a reactor dependency.
COPY event-contracts event-contracts
COPY docker/schema-registry/pom.xml docker/schema-registry/pom.xml
COPY services/connector-service/pom.xml services/connector-service/pom.xml
COPY services/change-service/pom.xml services/change-service/pom.xml
COPY services/change-service/src services/change-service/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl services/change-service -am -DskipTests package

FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jre-alpine-3.24@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime
# Apply Alpine's CVE-2026-77214 fix while retaining the pinned Temurin runtime.
RUN apk add --no-cache libexpat=2.9.0-r0

WORKDIR /app
RUN addgroup -S -g 10001 changeguard \
    && adduser -S -D -H -u 10001 -G changeguard changeguard
COPY --from=build --chown=10001:10001 /workspace/services/change-service/target/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider "http://127.0.0.1:${SERVER_PORT:-8082}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
