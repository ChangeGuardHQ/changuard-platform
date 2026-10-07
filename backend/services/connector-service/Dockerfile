# syntax=docker/dockerfile:1.27.1

# Build stage: compile the Spring Boot application with Java 21 and cache Maven dependencies.
FROM eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24 AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# Runtime stage: run the application in a minimal Alpine image.
FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24 AS runtime

WORKDIR /app
# Create a dedicated non-root user for the service to improve container security.
RUN addgroup -S -g 10001 changeguard \
    && adduser -S -D -H -u 10001 -G changeguard changeguard
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8081
# Health check verifies the app exposes the Spring Boot actuator health endpoint.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider "http://127.0.0.1:${SERVER_PORT:-8081}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
