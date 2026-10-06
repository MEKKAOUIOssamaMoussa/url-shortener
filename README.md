# URL Shortener

A high-performance URL shortener built with Java 21 and Spring Boot 4.1.

> **Work in progress**: Currently in Stage 0a (walking skeleton and CI pipeline).

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
