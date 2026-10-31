# URL Shortener

[![CI](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml)

A URL shortener built with Java 21 and Spring Boot 4.1.

> **Status**: Stage 1 complete: URL shortening and redirection API backed by PostgreSQL on Neon, deployed to Azure Container Apps by GitHub Actions (OIDC, no stored deploy credentials).

## Live Demo

Base URL: `https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io`
*(Scales to zero when idle: the first request after idle took 28.7 s in one measurement, mostly platform start before the app runs.)*

### 1. Create a Short URL
*(Rate limited to 10 requests per minute per IP; returns HTTP 429 Too Many Requests with Retry-After when exceeded.)*
```bash
curl -si -X POST https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/some/long/path"}'
```
Example response (`201 Created`):
```http
HTTP/2 201 
location: https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/<code-from-response>
content-type: application/json

{"code":"<code-from-response>","shortUrl":"https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/<code-from-response>","targetUrl":"https://example.com/some/long/path","createdAt":"2026-10-29T..."}
```

### 2. Follow the Redirect
```bash
curl -si https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/<code-from-response>
```
Example response (`302 Found`):
```http
HTTP/2 302 
location: https://example.com/some/long/path
```

## Running Locally

### Prerequisites
- Java 21 (Eclipse Temurin recommended)
- Docker Desktop or Docker engine

### Start Dependencies
```bash
# Start PostgreSQL only
docker compose up -d postgres

# Or start both PostgreSQL and Redis (for cache testing)
docker compose up -d
```

### Option 1: Run with Maven Wrapper
```bash
cd backend
./mvnw spring-boot:run
```
Access the application health endpoint at `http://localhost:8080/actuator/health`.

### Option 2: Run Locally with Redis Cache Enabled
By default, the cache is disabled (`shortener.cache.enabled=false`) and uses a no-op implementation. To enable Redis caching:
```bash
export SHORTENER_CACHE_ENABLED=true
# Default Redis host (localhost) and port (6379) are used automatically.
# To override Redis host and port, use standard Spring Data Redis environment variables:
# export SPRING_DATA_REDIS_HOST=localhost
# export SPRING_DATA_REDIS_PORT=6379

cd backend
./mvnw spring-boot:run
```
Inspect cached keys and TTLs in Redis:
```bash
docker compose exec redis redis-cli get url:<code-from-response>
docker compose exec redis redis-cli ttl url:<code-from-response>
```

### Option 3: Run with Docker
```bash
# Build the container image
docker build -t url-shortener-backend backend/

# Run the container
docker run -p 8080:8080 --rm url-shortener-backend
```
Access the application health endpoint at `http://localhost:8080/actuator/health`.

### Example API Usage

1. Create a short URL:
```bash
curl -si -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/some/long/path"}'
```

2. Follow the redirect:
```bash
curl -si http://localhost:8080/<code-from-response>
```
