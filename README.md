# URL Shortener

[![CI](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml)

A URL shortener built with Java 21 and Spring Boot 4.1.

> **Status**: Stage 0 complete: walking skeleton deployed to Azure Container Apps by GitHub Actions (OIDC, no stored credentials). No URL-shortening features yet.

Live demo: https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/api/hello (scales to zero when idle; the first request can take ~30 s).

## Running Locally

### Prerequisites
- Java 21 (Eclipse Temurin recommended)
- Docker Desktop or Docker engine

### Option 1: Run with Maven Wrapper
```bash
cd backend
./mvnw spring-boot:run
```
Access the application at `http://localhost:8080/api/hello` or `http://localhost:8080/actuator/health`.

### Option 2: Run with Docker
```bash
# Build the container image
docker build -t url-shortener-backend backend/

# Run the container
docker run -p 8080:8080 --rm url-shortener-backend
```
Access the endpoints at:
- `http://localhost:8080/api/hello`
- `http://localhost:8080/actuator/health`
