# URL Shortener

[![CI](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MEKKAOUIOssamaMoussa/url-shortener/actions/workflows/ci.yml)

A URL shortener built with Java 21 and Spring Boot 4.1.

> **Status**: Stages 0-5 and 7 done. Stage 6 (Prometheus/Grafana/k6 load testing) was skipped by decision, so the Stage 2 hypothesis that the cache helps with a remote database or under load remains **UNMEASURED**.

## Live Demo

Frontend: https://gentle-island-020fcf90f.3.azurestaticapps.net
*(Deploys happen on merge to `main` when the change touches `frontend/` (or `.github/workflows/frontend.yml`): Frontend CI runs the unit tests and the production build, then uploads that build to Azure Static Web Apps.)*

API base URL: `https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io`
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

### 3. Query Click Analytics
*(Counts are eventually consistent, lagging behind in-memory buffer flushes by up to 5 seconds, and lossy by design via at-most-once delivery. Endpoint is not rate limited and does not record clicks.)*
```bash
curl -si https://ca-shortener.proudmushroom-61e0888c.francecentral.azurecontainerapps.io/api/urls/<code-from-response>/stats
```
Example response (`200 OK`):
```http
HTTP/2 200
content-type: application/json

{"code":"<code-from-response>","totalClicks":42,"daily":[{"day":"2026-11-02","count":42}]}
```

## Running Locally

### Prerequisites
- Java 21 (Eclipse Temurin recommended)
- Node.js `>=24.15` (defined in `frontend/.nvmrc`; run `nvm use` in `frontend/`) and npm `>=11.0`
- Docker Desktop or Docker engine

### Start Dependencies
```bash
# Start PostgreSQL only
docker compose up -d postgres

# Or start both PostgreSQL and Redis (for cache testing)
docker compose up -d
```

### Start Backend
```bash
cd backend
./mvnw spring-boot:run
```
Access the application health endpoint at `http://localhost:8080/actuator/health`.

### Start Frontend (Angular SPA)
In a separate terminal, with the backend running on `http://localhost:8080`:
```bash
cd frontend
nvm use
npm install
npm start
```
Access the frontend in your browser at `http://localhost:4200`.

To execute frontend unit tests headless:
```bash
cd frontend
npm test -- --watch=false
```

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

### Option 3: Run Locally with Redis Rate Limiter Enabled
By default, the rate limiter uses an in-memory store (`shortener.rate-limit.store=memory`), which is exact for single-instance deployments like Azure Container Apps. To share rate limit counters across multiple application instances via Redis:
```bash
export SHORTENER_RATE_LIMIT_STORE=redis
# Optionally enable Redis caching simultaneously:
# export SHORTENER_CACHE_ENABLED=true

cd backend
./mvnw spring-boot:run
```
Inspect rate-limit keys, counters, and remaining TTLs (in milliseconds) in Redis:
```bash
docker compose exec redis redis-cli keys 'rl:*'
docker compose exec redis redis-cli get 'rl:127.0.0.1'
docker compose exec redis redis-cli pttl 'rl:127.0.0.1'
```

### Code Generation Strategy
By default, short codes are generated randomly (`shortener.code.strategy=random`). To use sequential, collision-free database codes scrambled with a 42-bit keyed Feistel cipher, set `shortener.code.strategy=sequence` (requires PostgreSQL sequence `short_code_seq`, provisioned by Flyway migration V2). In production, supply a private secret key via `shortener.code.secret` (environment variable `SHORTENER_CODE_SECRET`) to prevent sequence enumeration.

### Option 4: Run with Docker
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

3. Query click analytics:
```bash
curl -si http://localhost:8080/api/urls/<code-from-response>/stats
```
Example response (`200 OK`):
```json
{
  "code": "<code-from-response>",
  "totalClicks": 2,
  "daily": [
    {
      "day": "2026-11-02",
      "count": 2
    }
  ]
}
```
*Note*: Click counts are eventually consistent (lagging behind the in-memory buffer by up to the 5s flush interval) and lossy by design (at-most-once delivery, never blocking or throwing on the redirect path). Unknown codes return RFC 9457 (formerly RFC 7807) 404 ProblemDetail.
