# syntax=docker/dockerfile:1

# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy Maven wrapper files and project descriptor
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure wrapper script is executable and resolve dependencies to cache them
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copy application source code and package application
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as non-root user
RUN groupadd --system appgroup && useradd --system --gid appgroup --uid 10001 appuser

# Copy executable fat jar from build stage
COPY --from=builder /workspace/target/*.jar /app/app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
