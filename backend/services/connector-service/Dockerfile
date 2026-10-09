# syntax=mirror.gcr.io/docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Build stage: compile the Spring Boot application with Java 21 and cache Maven dependencies.
FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build

WORKDIR /workspace
COPY services/connector-service/.mvn .mvn
COPY services/connector-service/mvnw pom.xml ./
RUN chmod +x mvnw
COPY event-contracts event-contracts
COPY docker/schema-registry/pom.xml docker/schema-registry/pom.xml
COPY services/connector-service/pom.xml services/connector-service/pom.xml
COPY services/connector-service/src services/connector-service/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl services/connector-service -am -DskipTests package

# Runtime stage: run the application in a minimal Alpine image.
FROM mirror.gcr.io/library/eclipse-temurin:21.0.12.1_1-jre-alpine-3.24@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime

WORKDIR /app
# Create a dedicated non-root user for the service to improve container security.
RUN addgroup -S -g 10001 changeguard \
    && adduser -S -D -H -u 10001 -G changeguard changeguard
COPY --from=build --chown=10001:10001 /workspace/services/connector-service/target/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8081
# Health check verifies the app exposes the Spring Boot actuator health endpoint.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider "http://127.0.0.1:${SERVER_PORT:-8081}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
