# System Design & Architecture Decisions

> **Status**: Stage 0 complete (2026-10-08). Hypotheses below are kept and annotated with measured results.

---

## 1. Goal
A URL shortener built in small stages to learn system design, deployment, and CI/CD; features follow the plan (shorten/redirect, caching, rate limiting, ID generation, async analytics).

---

## 2. Stage 0 Architecture Decisions

* **Compute Platform**: **Azure Container Apps (ACA)** on the Consumption plan with `minReplicas = 0`.
  * *Rationale*: Scales to zero when idle, fitting within Azure's free grant (180,000 vCPU-seconds and 360,000 GiB-seconds per month) to minimize operational costs for a portfolio project.
* **Container Registry**: **GitHub Container Registry (GHCR)** (`ghcr.io`).
  * *Rationale*: Free for public images, integrated with GitHub Actions; image is public so Azure pulls it without any registry credential (it contains no secrets).
* **CI/CD & Cloud Auth**: **GitHub Actions with OpenID Connect (OIDC)** to Azure.
  * *Rationale*: Eliminates long-lived service principal secrets in repository settings; uses federated credentials for secure token exchange.
* **Database (Stage 1+)**: **PostgreSQL on Neon Serverless Postgres**.
  * *Rationale*: Free tier, standard Postgres (portable: one connection string), not tied to Azure credits. Wakes from suspend in ~0.5-2 s (provider-stated, not yet measured).
* **Two Environments / Local Development Stack**: **Local Docker Compose** (introduced in Stage 1).
  * *Rationale*: Local Docker Compose runs the full system-design stack (Postgres, later Redis, analytics, monitoring, load tests); Azure runs only a minimal public demo. Load-testing a free-tier host would only measure the host.

---

## 3. Stage 0: Decisions Verified in Practice

* **Container Apps Environment Logging**: Created with logs destination `none` (no Log Analytics, to avoid ingestion cost). Verified: live log streaming still works.
* **Replica Scaling & Cost Ceiling**: `maxReplicas = 1` as a cost ceiling; `minReplicas = 0` (scale to zero, verified via an empty replica list after ~10 min idle).
* **Image Tagging & Verification**: Images tagged with the full commit SHA; deploy verified by comparing the running revision's image tag with `git rev-parse HEAD`.
* **Deploy Job Verification**: The workflow checks that configured image == expected SHA image, latest revision == latest ready revision, and `/actuator/health` == UP with a 2-minute timeout.
* **Image Size Optimization**: A separate `RUN chown` duplicated the 23 MB jar in a second layer. Fixed with `COPY --chown`: Docker reported 556 MB -> 511 MB (Docker Desktop's size column appears to count the jar roughly twice).
* **OIDC Subject Matching**: GitHub presented an ID-qualified subject (`repo:<owner>@<id>/<repo>@<id>:ref:refs/heads/main`). A name-only federated credential failed with `AADSTS700213`. IDs are immutable, so the trust rule can't be matched by a different account/repo reusing the same names.

---

## 4. Hypotheses to Measure Later

1. **Cold Start Latency**:
   * *Hypothesis*: Java 21 + Spring Boot 4.x running in ACA with scale-to-zero will experience an initial cold start latency of 3–6 seconds upon first request after idle suspension. We will evaluate startup optimizations (e.g., CDS, JVM tuning, or native compilation) in later stages.
   * *Result (n=1, 2026-10-07)*: 27.4 s total for the first request after scale-to-zero; Spring reported 'Started in 2.752 s'. So ~90% of the wait happened before application code ran (platform scheduling, likely image pull and container start; not yet broken down). The prediction was wrong. Needs more samples before drawing conclusions.
2. **Operational Cost**:
   * *Hypothesis*: Steady-state monthly cloud expenditure will remain near \$0.00 by remaining within ACA's free tier consumption grant and Neon's serverless free tier.
   * *Result*: Not yet measured. To check: Azure Cost Management after the first month.
3. **Code Collision Frequency**:
   * *Hypothesis*: With 7-char base62 codes, collisions should never be observed at demo scale.
   * *Result*: To be evaluated as dataset grows.

---

## 5. Open Questions

* What is the p95 and p99 redirect latency under scale-to-zero vs. keeping 1 minimum replica active?
* Will Neon serverless connection latency require connection pooling adjustments (e.g., HikariCP sizing or PgBouncer) during burst traffic?
* At what scale does caching (Redis) become mandatory to protect Neon compute hours?
* What makes up the ~24 s of cold start outside Spring, and is a smaller image worth it?

---

## 6. Stage 1: Persistence Decisions (Step 1)

* **Schema Evolution via Flyway with Hibernate Validation**: Schema migrations are managed solely via versioned SQL scripts in Flyway (`V1__create_short_url.sql`). Hibernate DDL generation is restricted to `validate` (`spring.jpa.hibernate.ddl-auto: validate`), preventing runtime schema mutations and ensuring entities stay in lockstep with Flyway.
* **Testcontainers over In-Memory H2**: Tests run against real `postgres:18` instances wired via Spring Boot's `@ServiceConnection` and Testcontainers. This guarantees identical dialect, type behaviors (`TIMESTAMPTZ`, `BIGSERIAL`), and constraint enforcement without discrepancy between test and production engines.
* **Open Session In View (OSIV) Disabled**: `spring.jpa.open-in-view` is set to `false`. Disabling OSIV prevents Hibernate sessions and database connections from staying open across the full HTTP request lifecycle, avoiding connection pool starvation and enforcing clear transactional boundaries.
* **Database Clock Authority with Single-Statement `RETURNING`**: The `createdAt` column is defaulted to `now()` in the PostgreSQL schema and mapped on `ShortUrl` using `@Generated` with `insertable = false, updatable = false`. Hibernate ORM 7 leverages PostgreSQL's `RETURNING` clause to fetch both `id` and `created_at` in a single SQL operation:
  ```sql
  insert into short_url (code,target_url) values (?,?) returning id,created_at
  ```
  This maintains the database clock as the single source of truth without incurring an extra `SELECT` round-trip.

---

## 7. Stage 1: API & Redirection Decisions (Step 2)

* **HTTP 302 Found vs 301 Moved Permanently**: Redirection uses HTTP 302 (Found) instead of 301 (Moved Permanently). Browsers aggressively cache 301 responses on the client side, which would bypass the backend on subsequent redirects and prevent click tracking and analytics (introduced in Stage 5).
* **Random Code Generation with DB Unique Constraint & Retry Outside Transactions**: Short codes are generated as random 7-character base-62 strings from `[0-9A-Za-z]`. Collision detection is anchored in the database's named unique constraint (`uq_short_url_code`). The service retry loop operates outside any ambient `@Transactional` context so that each failed insertion attempt rolls back cleanly in its own isolated transaction without aborting the surrounding connection state, and retries with a fresh code up to 3 times before failing.

---

## 8. Stage 1: Cloud Persistence Decisions (Step 3)

* **Database Password in Container Apps Secret (Not GitHub Secrets)**:
  * *Rationale*: The CI deployment workflow has no need to know or manage database credentials; it only authenticates via OIDC and deploys container image tags (`az containerapp update --image`). Storing the database password directly in Azure Container Apps as a secret (`db-password`) and referencing it in the container environment via `secretref:db-password` follows least-privilege principles and prevents sensitive database secrets from residing in CI.
* **Direct Host over Neon Connection Pooler**:
  * *Rationale*: Neon’s docs recommend the direct (non-pooled) connection for schema migrations. The pooled endpoint runs PgBouncer in transaction mode, which doesn’t keep session state between transactions. The app already pools connections in-process with HikariCP, so an extra pooler adds nothing at this scale. (Not verified: exactly which Flyway operations would break through the pooler.)
* **Neon Free Tier Idle Suspension & Wake-up**:
  * *Rationale*: Neon's free tier automatically suspends compute endpoints after a period of inactivity. When a request arrives after an idle period, the initial response latency includes the Neon database compute wake-up alongside the Container App scale-to-zero container start.

---

## 9. Stage 2: Cache-Aside Redirect Path Decisions & Hypotheses

* **Cache-Aside Pattern vs Alternatives (Read-Through, Write-Through)**:
  * *Hypothesis*: Cache-aside on the read path decouples the application from cache availability. The database remains the primary authoritative data store, and the cache acts purely as an ephemeral performance optimization. If Redis fails or is unreachable, the application degrades gracefully to reading from PostgreSQL with no user-facing errors. In contrast, write-through or read-through mechanisms tightly couple writes to cache health and require distributed transaction semantics or fallback logic in the persistence layer.
* **`UrlCache` Abstraction and `NoOpUrlCache`**:
  * *Hypothesis*: Abstracting cache operations behind `UrlCache` (with `get` and `put`) combined with conditional configuration (`@ConditionalOnProperty(prefix = "shortener.cache", name = "enabled", havingValue = "true")`) allows the production Azure deployment (which has no Redis) to operate completely independent of Redis libraries and connection state using `NoOpUrlCache`.
* **Data Immutability & Zero Invalidation Complexity**:
  * *Hypothesis*: Short URL mappings (`code -> targetUrl`) are immutable—there are no update endpoints or entity mutators. Therefore, cached mappings can never become stale relative to the database, eliminating cache invalidation complexity (cache stampede on invalidation, race conditions, dual-write consistency).
* **24-Hour Cache TTL**:
  * *Hypothesis*: A 24-hour TTL (`shortener.cache.ttl: 24h`) set atomically on `SET` balances memory retention and eviction churn. Because mappings are immutable, TTL does not serve staleness protection; it serves as a safety net to purge orphaned keys if codes are ever deleted in future stages or if cold keys consume Redis memory, with `allkeys-lru` handling memory exhaustion. The 24h number is an unmeasured baseline guess that will require production workload profiling to evaluate.
* **Cache Lookup Outside Database Transactions**:
  * *Hypothesis*: Performing the cache lookup in `ShortUrlService.findTargetUrl(String code)` outside any ambient `@Transactional` context ensures that cache hits incur 0 database interactions—no database connections acquired from HikariCP, no transaction boundaries, and no database round-trips. Database access is only invoked on a cache miss via a `readOnly = true` transactional lookup.
* **Decoupling Redis Health from `/actuator/health`**:
  * *Hypothesis*: The cache is an optimization, not a system requirement. Disabling the auto-configured Redis health indicator (`management.health.redis.enabled: false`) ensures that a Redis failure or missing Redis instance does not bring down the application's readiness/liveness status or trigger false-positive container restarts in environments like Azure where Redis is absent.
* **Timeout Semantics: Refused vs Hanging Connections**:
  * *Hypothesis*: Short Redis timeouts (`spring.data.redis.connect-timeout: 200ms`, `spring.data.redis.timeout: 200ms`) bound latency degradation when Redis is slow or non-responsive. However, tests against a closed port (e.g. `localhost:65432`) cover connection refusal/absence (which fails immediately at the TCP level), not connection hanging/slowness. Validating the 200 ms timeout requires network latency injection or hanging socket proxies (to be addressed in future load/chaos testing).
  * *Annotation & Experiment Result*: Hypothesis confirmed for the closed-port case, but that case was not the problem. When tested against an unallocated port (`localhost:65432`) with a 5 s command timeout and warm-up, the redirect took only 39 ms (n=1). A client that has never connected receives an immediate TCP RST (`Connection refused`), failing fast with `Unable to connect to Redis` without queueing commands. The earlier 794 ms measurement on that closed port was warm-up (initial Netty event loop initialization and JVM class loading), not network blocking.

---

## 10. Stage 2: Fail-Fast Disconnected Behavior & Benchmark (Step A Follow-Up)

* **The Problem (Command Queueing on Disconnect)**:
  * When Redis stopped after having established an active connection, redirect latency jumped to ~0.42 s (initial n=1 measurement: 421 ms; benchmark median: 418.5 ms). Lettuce's default behavior (`ClientOptions.DisconnectedBehavior.DEFAULT` / `ACCEPT_COMMANDS`) buffers commands in an internal queue while disconnected, waiting for reconnection until `commandTimeout` expires. Each redirect suffered two serial command timeouts: 200 ms on `get` followed by 200 ms on `put` (`Redis command timed out` logged at +207 ms and +414 ms).
* **The Decision & Alternatives**:
  * *Decision*: Configure Lettuce via `LettuceClientOptionsBuilderCustomizer` to use `ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`. When disconnected, commands fail immediately without entering the queue. Spring Data Redis translates the rejection into `DataAccessException`, allowing `RedisUrlCache` to degrade instantly to PostgreSQL without delay.
  * *Alternative 1: Keep default (buffer commands)*: Rejected due to severe latency penalty (~420 ms per redirect) during a cache outage.
  * *Alternative 2: Lower timeouts (e.g. 10–20 ms)*: Rejected as fragile. Normal network jitter or brief GC pauses under load would cause spurious timeouts on healthy connections.
  * *Alternative 3: Circuit breaker (e.g. Resilience4j)*: Deferred. Adds an extra dependency, configuration surface, and state machine. The cache is already an optional optimization with graceful degradation.
  * *Rationale*: `REJECT_COMMANDS` is native to Lettuce, requires zero extra dependencies, preserves configured socket options (`connect-timeout=200ms`), and requires zero changes to `RedisUrlCache`.
* **Integration Test Verification (n=1, Command Timeout 5 s)**:
  * In `RedisDownHealthTest`, reproducing the real disconnect scenario (connect first, then stop container via Docker API):
    * *Without fix (`DEFAULT`)*: **10,027 ms** (5 s on `get` + 5 s on `put`). Test failed against `< 1000 ms` assertion.
    * *With fix (`REJECT_COMMANDS`)*: **19 ms**. Commands rejected immediately, fallback to Postgres completed cleanly.
* **Manual Docker Compose Benchmark (n=10 after 1 Warm-up Request Each)**:
  > [!NOTE]
  > Local Postgres runs on the same machine (localhost Docker Compose), so database latency here (~7–8 ms) does not represent the Azure/Neon serverless round-trip latency.
  * **a. Cache disabled (defaults, `NoOpUrlCache`)**:
    * Raw (s): `0.012454, 0.008528, 0.008218, 0.009406, 0.010665, 0.009299, 0.008891, 0.007802, 0.008353, 0.008393`
    * Summary: Min = **7.80 ms**, Median = **8.71 ms**, Max = **12.45 ms**
  * **b. Cache enabled, Redis UP (cache hits)**:
    * Raw (s): `0.009763, 0.006625, 0.008816, 0.007812, 0.006840, 0.006754, 0.007262, 0.007436, 0.008222, 0.008251`
    * Summary: Min = **6.63 ms**, Median = **7.62 ms**, Max = **9.76 ms**
  * **c. Cache enabled, Redis stopped, BEFORE fix (`DEFAULT` queueing, 200 ms timeouts)**:
    * Raw (s): `0.420798, 0.434366, 0.418432, 0.419918, 0.419002, 0.418517, 0.416214, 0.416515, 0.416904, 0.417715`
    * Summary: Min = **416.21 ms**, Median = **418.47 ms**, Max = **434.37 ms**
  * **d. Cache enabled, Redis stopped, AFTER fix (`REJECT_COMMANDS`)**:
    * Raw (s): `0.011482, 0.010864, 0.013773, 0.012269, 0.008358, 0.009632, 0.010153, 0.019112, 0.015683, 0.010674`
    * Summary: Min = **8.36 ms**, Median = **11.17 ms**, Max = **19.11 ms**
* **Reconnection & Recovery Verification**:
  * After `docker compose start redis`, Lettuce's background `ConnectionWatchdog` successfully reconnected (`Reconnected to localhost/<unresolved>:6379` logged in ~24 s, reflecting exponential backoff retry intervals). Subsequent redirects immediately resumed caching in Redis (verified via `redis-cli get`).
* **Known Limits & Hypotheses**:
  * *Connected-but-slow Redis*: If Redis is connected but hanging or responding slowly, requests will still be bounded by the 200 ms command timeout (up to ~400 ms per redirect). `REJECT_COMMANDS` only protects against *disconnected* states.
  * *Log Flood*: While Redis is down, every redirect logs two WARN lines (`Redis error on get...` and `Redis error on put...`). Under sustained high traffic, this produces log volume until Redis recovers.
  * *Brief Reconnect Window*: During Lettuce reconnection backoff windows, commands are rejected and fall back to Postgres even if Redis has just come back up, until the next watchdog reconnect tick completes.
  * *Future Circuit Breaker*: A circuit breaker remains a future option if connected-but-slow Redis or log rate-limiting becomes necessary in production.
