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
   * *Result*: MEASURED 2026-11-01 on Neon: 73 rows in `short_url`; `short_code_seq` `is_called = false` (sequence exists via Flyway V2, never called because Azure runs `RANDOM`). At $n=73$, the next-code collision probability is $\frac{73}{3{,}521{,}614{,}606{,}208} \approx 2.07 \times 10^{-11} \approx 2\text{e-}11$. The hypothesis holds firmly at demo scale.

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
  * *Hypothesis*: Cache-aside on the read path decouples the application from cache availability. The database remains the primary authoritative data store, and the cache acts purely as an ephemeral performance optimization. If Redis fails or is unreachable, the application degrades gracefully to reading from PostgreSQL with no user-facing errors. In contrast, write-through or read-through mechanisms tightly couple writes to cache health and require fallback logic in the persistence layer.
* **`UrlCache` Abstraction and `NoOpUrlCache`**:
  * *Hypothesis*: Abstracting cache operations behind `UrlCache` (with `get` and `put`) combined with conditional configuration (`@ConditionalOnProperty(prefix = "shortener.cache", name = "enabled", havingValue = "true")`) ensures that when the cache is disabled (`NoOpUrlCache`), no cache commands are ever sent. However, because the Redis client is on the classpath, Spring Boot still creates the connection factory bean at startup even when the cache is disabled. Verification on 2026-10-30 after deploying to Azure showed `/actuator/health` UP, `POST /api/urls` 201, and redirect 302 working normally; inspection of `az containerapp logs` (53 lines) contained no Redis or Lettuce log messages (no Redis activity observed in application logs, though lack of log output is not conclusive proof that no socket connection was ever attempted).
* **Data Immutability & Staleness vs Stampede**:
  * *Hypothesis*: Short URL mappings (`code -> targetUrl`) are immutable—there are no update endpoints or entity mutators. Therefore, immutability removes staleness (no update/delete exists, so a read-then-put race condition can never cache an outdated value, making cache invalidation unnecessary). However, immutability does not prevent cache stampede on TTL expiry or a cold cache; the stampede decision (no protection for now; Stage 6 hypothesis) is recorded in the Stage 2 step B notes below.
* **24-Hour Cache TTL**:
  * *Hypothesis*: A 24-hour TTL (`shortener.cache.ttl: 24h`) set atomically on `SET` balances memory retention and eviction churn. Because mappings are immutable, TTL does not serve staleness protection; it serves as a safety net to purge orphaned keys if codes are ever deleted in future stages or if cold keys consume Redis memory, with `allkeys-lru` handling memory exhaustion. The 24h number is an unmeasured baseline guess that will require production workload profiling to evaluate.
* **Cache Lookup Outside Database Transactions**:
  * *Hypothesis*: Performing the cache lookup in `ShortUrlService.findTargetUrl(String code)` outside any `@Transactional` context ensures that cache hits incur 0 database interactions—`findTargetUrl` has no `@Transactional`, so a cache hit never calls the repository, acquires no connection from HikariCP, and initiates no database work. The repository is only called on a cache miss.
* **Decoupling Redis Health from `/actuator/health`**:
  * *Rationale*: The cache is an optimization, not a system requirement. Disabling the auto-configured Redis health indicator (`management.health.redis.enabled: false`) ensures that a Redis outage does not bring `/actuator/health` DOWN. In CI/CD, the deploy job polls `/actuator/health` until `UP`, so an active Redis health contributor would cause every Azure deployment to fail because Azure has no Redis instance.
* **Timeout Semantics: Refused vs Hanging Connections**:
  * *Hypothesis*: Short Redis timeouts (`spring.data.redis.connect-timeout: 200ms`, `spring.data.redis.timeout: 200ms`) bound latency degradation when Redis is slow or non-responsive. However, tests against a closed port (e.g. `localhost:65432`) cover connection refusal/absence (which fails immediately at the TCP level), not connection hanging/slowness. Validating the 200 ms timeout requires network latency injection or hanging socket proxies (to be addressed in future load/chaos testing).
  * *Annotation & Experiment Result*: Hypothesis confirmed for the closed-port case, but that case was not the problem. When tested against an unallocated port (`localhost:65432`) with a 5 s command timeout and warm-up, the redirect took only 39 ms (n=1). A client that has never connected receives an immediate TCP RST (`Connection refused`), failing fast with `Unable to connect to Redis` without queueing commands. The earlier 794 ms measurement on that closed port was likely warm-up (cause not measured, as subsequent calls took only 39 ms), not network blocking.

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
  * **Benchmark Interpretation**:
    * *Comparison a vs b (Cache Disabled vs Cache Hits)*: Medians are 8.71 ms vs 7.62 ms with overlapping ranges (7.80–12.45 ms vs 6.63–9.76 ms, n=10). At local scale on the same machine, caching shows no measurable benefit because local PostgreSQL is co-located and request handling overhead dominates. The expected performance and throughput benefit of caching when querying a remote database (Neon serverless) or under heavy load remains an unverified **hypothesis for Stage 6**.
    * *Comparison d vs a (Cache Enabled with Redis Down vs Cache Disabled)*: Median 11.17 ms vs 8.71 ms (~ +2.5 ms difference), representing the small constant overhead of two rejected Redis calls (`get` and `put`), exception translation, and WARN logging per request while Redis is down.
* **Reconnection & Recovery Verification**:
  * Lettuce's background `ConnectionWatchdog` reconnected after Redis restart (time not measured; logged `Reconnected to localhost/<unresolved>:6379`). Subsequent redirects immediately resumed caching in Redis (verified via `docker compose exec redis redis-cli get url:FQKaTeP`). Because Redis runs with `--save ""` (no persistence volume or RDB snapshot), the restarted Redis container started completely empty; retrieving the key confirms a fresh, successful write after reconnection.
* **Known Limits & Hypotheses**:
  * *Connected-but-slow Redis*: If Redis is connected but hanging or responding slowly, requests will still be bounded by the 200 ms command timeout (up to ~400 ms per redirect). `REJECT_COMMANDS` only protects against *disconnected* states.
  * *Log Flood*: While Redis is down, every redirect logs two WARN lines (`Redis error on get...` and `Redis error on put...`). Under sustained high traffic, this produces log volume until Redis recovers.
  * *Brief Reconnect Window*: During Lettuce reconnection backoff windows, commands are rejected and fall back to Postgres even if Redis has just come back up, until the next watchdog reconnect tick completes.
  * *Future Circuit Breaker*: A circuit breaker remains a future option if connected-but-slow Redis or log rate-limiting becomes necessary in production.

---

## 11. Stage 2: Write-on-Create, Negative Caching, & Stampede Decisions (Step B)

* **Write-on-Create (Database-First Outside Transactions)**:
  * *Decision*: When a link is shortened via `POST /api/urls`, `ShortUrlService.create` writes to PostgreSQL first via `repository.saveAndFlush`. Only upon successful database persistence is `urlCache.put(saved.getCode(), saved.getTargetUrl())` invoked.
  * *Transaction Isolation*: The operation occurs outside any ambient `@Transactional` boundary. If code generation encounters a collision on `uq_short_url_code` (or another constraint) and retries, the failed attempt never writes to Redis. If retries are exhausted or insertion fails, nothing is cached. Only the final persisted `(code, targetUrl)` mapping enters the cache.
  * *Benefit*: The very first redirect request for a newly created link hits Redis immediately without querying PostgreSQL, completely eliminating cold-link first-redirect database misses.

* **Negative Caching of Unknown Codes**:
  * *Decision*: Cache misses for nonexistent codes are cached in Redis via `urlCache.putMissing(code)` with an empty-string sentinel (`""`) and a short TTL (`shortener.cache.negative-ttl`, default `60s`).
  * *`CacheLookup` Type System*: Replaced `Optional<String>` on `UrlCache.get` with a sealed interface `CacheLookup` permitting `Hit(String targetUrl)`, `KnownMissing()`, and `Miss()`. Handled in `ShortUrlService.findTargetUrl` via an exhaustive switch expression using pattern matching.
  * *Sentinel Choice & Safety Guarantee*:
    * Flyway migration `V1__create_short_url.sql` defines `target_url text not null`. While PostgreSQL's `NOT NULL` constraint permits empty strings `''`, the application API is the sole writer to this table and strictly rejects blank strings via bean validation (`@NotBlank` and custom `@ValidUrl` on `CreateShortUrlRequest`).
    * Therefore, an empty string `""` can never be a legitimate stored target URL in PostgreSQL. Using `""` as the missing sentinel in Redis is unambiguous and requires no additional key namespaces or secondary data structures.
  * *Negative TTL Rationale (60 s)*:
    * The 60-second TTL is an unmeasured baseline guess. It is deliberately much shorter than the 24-hour hit TTL to minimize the window during which an externally created link might encounter a stale cached 404, while long enough to blunt repeated lookups for the same missing code.
    * In practice, because `urlCache.put` uses the exact same key (`url:<code>`), any subsequent creation of that code atomically overwrites the negative sentinel with the real target URL.
  * *Honest Boundaries & Limits*:
    * Negative caching protects the database against repeated lookups of the *same* nonexistent code (e.g. broken client retry loops, shared dead links, crawler loops).
    * It does **not** protect against random-code enumeration or dictionary scanning attacks (where an attacker generates millions of distinct random codes). In that scenario, each lookup produces a distinct cache key, offering zero hit-rate benefit while flooding Redis memory and causing thrashing under `allkeys-lru` eviction.
    * The proper structural defense against scanning attacks is **Rate Limiting** (deferred to Stage 3), not negative caching.

* **Cache Stampede (Thundering Herd) Decision**:
  * *Decision*: No single-flight locking, distributed locks, probabilistic early expiration (XFetch), or TTL jitter are implemented in application code for Stage 2.
  * *Rationale*:
    1. *Single-Replica Deployment*: Azure Container Apps runs with `maxReplicas = 1`. Distributed lock managers (e.g. Redlock) introduce operational fragility, latency overhead, and external dependencies disproportionate to single-container scale.
    2. *In-Process Synchronization Limits*: In-process single-flight mechanisms (e.g. `ConcurrentHashMap` of flight promises) only coordinate threads within a single JVM instance and add complexity to cold paths.
    3. *Bounded Database Contention*: Database lookups on miss query an indexed unique key (`WHERE su1_0.code = ?`), executing in under a millisecond on warm PostgreSQL pages. Furthermore, HikariCP bounds active database connections (default pool size 10), preventing unbound connection surges from overwhelming the database engine.
    4. *Natural TTL Desynchronization*: Because links are written to cache at creation time across arbitrary user interactions, their 24-hour expirations naturally disperse over time rather than expiring simultaneously in coordinated batches.
  * *Future Evaluation*: Whether stampede protection is necessary under extreme concurrency will be empirically tested under simulated load using k6 in Stage 6.

---

## 12. Stage 3: Rate Limiting on Creation Path Decisions & Hypotheses (Step A)

* **Why Rate Limit the Creation Path (`POST /api/urls`)**:
  * *Hypothesis*: A public URL shortener is an attractive target for automated spam, bot enumeration, and resource exhaustion attacks. Allowing unthrottled link creation invites malicious actors to flood the database with bogus URLs, exhausting storage, indexing bandwidth, and code generation space.
  * *Targeted Protection*: Link creation (`POST /api/urls`) is throttled to a default of 10 requests per 1-minute window per client IP. In contrast, redirection (`GET /{code}`) is intentionally unthrottled: redirects are lightweight (served via Redis cache-aside), and throttling redirects risks penalizing legitimate human consumers behind shared corporate or mobile carrier NAT gateways.

* **Algorithm Choice: Fixed Window Counter vs. Token Bucket / Sliding Window**:
  * *Decision*: An in-memory fixed window counter with atomic per-window state swaps.
  * *Bounded Memory with Per-Window Reset*: Memory is bounded across windows because when the window index advances, the rate limiter atomically swaps an immutable holder containing the active window's `ConcurrentHashMap<String, AtomicInteger>`. The old window's map becomes unreferenced and is promptly reclaimed by garbage collection.
  * *Memory Boundary & Known Limit*: While memory is bounded across window transitions, within any single active 1-minute window the map scales linearly with the number of distinct client IPs encountered. A distributed flood involving millions of unique client IPs inside a single minute window remains a known memory limit.
  * *Known Flaw (Boundary Burst)*: As with all fixed-window algorithms, a client can theoretically achieve up to $2 \times \text{limit}$ requests across a window boundary (e.g., 10 requests at $T_{59s}$ and 10 requests at $T_{61s}$). Token bucket or sliding log algorithms smooth out boundary bursts, but require continuous per-key timestamp tracking or eviction queues. For our single-replica threat model, the bounded simplicity of fixed-window counters provides sufficient spam deterrence without algorithmic complexity.
  * *Boundary Swap Concurrency & State Imprecision*: When the window index advances, threads race to install a new `WindowState` via `compareAndSet`. A concurrent thread that sampled the clock just prior to the swap may still increment the old window's map. This minor boundary imprecision is acceptable for fixed window rate limiting.

* **Single-Replica In-Memory Exactness**:
  * *Hypothesis*: Azure Container Apps runs with `maxReplicas = 1`. In a single-replica deployment, an in-memory counter is exact: there are no multiple JVM instances with fractured local counters, so an in-memory limiter avoids the latency overhead and failure modes of distributed Redis calls on the creation path.
  * *Default Protection*: Rate limiting is enabled by default (`shortener.rate-limit.enabled=true`) so the Azure deployment is shielded immediately without requiring explicit configuration changes.

* **Servlet Filter (`RateLimitFilter`) vs. Handler Interceptor**:
  * *Decision*: Implemented as a `OncePerRequestFilter` rather than a Spring MVC `HandlerInterceptor`.
  * *Rationale*: A servlet filter rejects excessive requests as early as possible in the processing pipeline, avoiding Spring MVC controller dispatching, model resolution, and request body deserialization.
  * *RFC 9457 Error Response*: On rejection (`HTTP 429 Too Many Requests`), the filter directly writes an RFC 9457 `application/problem+json` body (`title: "Too Many Requests"`, `status: 429`, `detail` specifying retry duration, and `instance: "/api/urls"`) and sets the `Retry-After` header using Spring Boot's autoconfigured Jackson 3 `ObjectMapper`.

* **10 Requests / 1 Minute Baseline**:
  * *Rationale*: The 10/min threshold is an unmeasured baseline guess. For normal human usage, creating 10 short links in 60 seconds is well above standard interactive needs, while sufficiently restrictive to halt simple brute-force bots and runaway client retry loops. Future load testing with k6 in Stage 6 will refine this threshold.

* **Known Gap: Ingress Proxy & `remoteAddr` on Azure Container Apps**:
  * *Limitation*: In this step, the client identity is derived purely from `request.getRemoteAddr()`. Behind reverse proxies, load balancers, or Azure Container Apps Envoy ingress, `request.getRemoteAddr()` may report the internal proxy IP rather than the true originating client IP, effectively applying the 10/min limit globally across all users sharing that ingress node.
  * *Next Step*: Verifying actual Azure headers and configuring proper proxy forward handling (`X-Forwarded-For` / `server.forward-headers-strategy`) is deferred to Step B for live cloud verification.

---

## 13. Stage 3: Client IP Resolution Behind Ingress Decisions & Hypotheses (Step B)

* **Azure Measurements (2026-10-31, Tomcat Access Log %a and X-Forwarded-For)**:
  * *Ingress Proxy Identity (measured, 2026-10-31, n=2 ingress instances)*:
    Inspection of Tomcat access logs (`%a` and `%{X-Forwarded-For}i`) on Azure Container Apps revealed that `request.getRemoteAddr()` reported internal ingress proxy addresses: `100.100.0.172` and `100.100.0.11` (both within `100.64.0.0/10` Carrier-Grade NAT, RFC 6598).
    *Effect*: Without proxy header resolution, a single client's POST requests were load-balanced across the two proxy IPs, allowing 19 requests in one window with limit 10, while simultaneously collapsing all distinct clients behind the ingress into those two shared keys.
  * *Header Appending Behavior (measured, 2026-10-31, n=1 probe)*:
    Sending a request with `X-Forwarded-For: 6.6.6.6` arrived at the container as `6.6.6.6, 141.98.208.14`. The Azure ingress appends the observed upstream IP to any client-provided header rather than stripping or overwriting it. Taking the leftmost IP would allow any client to forge arbitrary rate-limit keys by prepending spoofed headers.
  * *Multi-IP Client Egress (measured, 2026-10-31, n=2 client IPs)*:
    The same home client connection egressed as two distinct public IPs (`141.98.208.14` and `141.98.208.15`), giving that single client multiple independent rate-limit quotas.
  * *Boundary Burst (measured, 2026-10-31, n=23 allowed)*:
    A burst across the 1-minute window boundary allowed 23 requests in ~10 seconds across the boundary transition, confirming the theoretical $2 \times \text{limit}$ fixed-window characteristic live.

* **Resolution Mechanism: Tomcat `RemoteIpValve` via `server.forward-headers-strategy: native`**:
  * *Decision*: Set `server.forward-headers-strategy: native` in `application.yml`.
  * *Why Rightmost-Trusted, Not Leftmost*:
    `RemoteIpValve` inspects `X-Forwarded-For` from right to left, discarding IP addresses that match trusted internal proxies until it encounters the first untrusted IP. This untrusted IP is established as the authentic remote address (`request.getRemoteAddr()`). If an attacker prepends spoofed IPs on the left (e.g. `203.0.113.99, 198.51.100.50`), the rightmost untrusted IP (`198.51.100.50`) is selected, defeating header spoofing.
  * *No Custom Application Parsing*:
    `RateLimitFilter` continues to read `request.getRemoteAddr()`. Delegating forward header resolution to the servlet container valve avoids custom, error-prone header parsing in application code.

* **Internal Proxies Matching**:
  * *Arithmetic & Defaults*:
    Spring Boot 4.1.1 (`TomcatServerProperties$Remoteip`) and Tomcat 11 (`RemoteIpValve`) default `internal-proxies` to:
    `"192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, fe80::/10, ::1/128"`.
    `100.64.0.0/10` covers the IPv4 range `100.64.0.0` to `100.127.255.255`. Both Azure ingress addresses (`100.100.0.11` and `100.100.0.172`) fall strictly within this subnet ($64 \le 100 \le 127$). Therefore, we rely on the container default without setting custom proxy regexes or CIDR properties.
    The live check after deploy will serve as final operational verification.

* **Scheme Isolation**:
  * While `RemoteIpValve` updates `request.getScheme()` to `"https"` when `X-Forwarded-Proto: https` is present, `ShortUrlController` generates short URLs and redirect targets using `properties.baseUrl()` and explicit database targets, ensuring absolute URL construction remains completely decoupled and unaffected.

* **End-to-End Test Verification**:
  * Verified in `ClientIpResolutionEndToEndTest` running a real embedded Tomcat server (`@SpringBootTest(webEnvironment = RANDOM_PORT)`):
    * *Independence*: 10 POSTs for `198.51.100.30` followed by 1 POST for `198.51.100.31` proved that independent client IPs maintain isolated counters (without `forward-headers-strategy: native`, both mapped to loopback `127.0.0.1` and failed with 429).
    * *Rate Limit Enforcement*: 10 POSTs for `6.6.6.6, 198.51.100.40` succeeded (201) and the 11th was rejected with 429.
    * *Spoof Resistance*: After exhausting `198.51.100.50`, sending `203.0.113.99, 198.51.100.50` was rejected with 429, verifying that injecting a fake leftmost client IP does not evade the limit.
    * *Test Order Independence*: Each test operates on a distinct IP range (`.30/.31`, `.40`, `.50`), ensuring zero cross-test interference under container IP resolution.

* **Known Limitations of Per-IP Rate Limiting**:
  * *Coarseness in Both Directions*:
    * Many clients behind a shared egress/NAT gateway (e.g. corporate networks, university campuses, mobile carrier CGNAT) share a single rate-limit quota, risking collateral throttling of legitimate users.
    * A single client distributed across multiple egress IPs (e.g. multi-homed ISP connections, observed live with `.14` and `.15`) receives multiple distinct rate-limit quotas.
  * *Fixed Window Burst*:
    * Fixed-window boundaries allow up to $2 \times \text{limit}$ requests across the boundary seam (23 allowed in ~10 s observed live).
* **Live Azure Verification (2026-10-31, post-deployment)**:
  * Deployed commit `24f584a` to Azure Container Apps.
  * Verified live with two concurrent clients from distinct public IPs:
    * Azure Cloud Shell (`135.220.92.108`): sent 11 POST requests; first 10 returned HTTP 201, 11th returned HTTP 429 at 16:48:01 UTC.
    * WSL home client (`141.98.208.14` or `.15`; this client alternates between two egress IPs, exact one not captured): sent a POST request at exactly 16:48:01 UTC within the same minute window; returned HTTP 201.
  * *Outcome*: Proved that per-client IP rate limiting is isolated and functional in production behind Azure's Envoy ingress proxies. Pre-fix behavior had allowed 19 requests across proxies because `request.getRemoteAddr()` had resolved to the two internal proxy IPs (`100.100.0.11` and `100.100.0.172`).

---

## 14. Stage 3: Redis Distributed Rate Limiter Decisions & Hypotheses (Step C)

* **Motivation: Multi-Instance Rate Limiting vs Single-Replica Exactness**:
  * *Single-Replica vs Multi-Replica*: In Stage 3 Step A, `InMemoryFixedWindowRateLimiter` was chosen for Azure Container Apps because Azure runs with `maxReplicas = 1`. On a single replica, in-memory is exact and incurs zero network latency. However, if the service scales horizontally across multiple replicas, each replica maintains its own isolated in-memory map. A client's traffic distributed round-robin across $N$ instances would effectively receive an aggregate limit of $N \times \text{limit}$.
  * *Distributed Store*: `RedisFixedWindowRateLimiter` provides a centralized counter in Redis, ensuring all application instances share the exact same quota per client IP (`rl:<clientKey>`).
  * *Local Full Stack Only*: This feature is designed for local multi-instance testing and environments with Redis infrastructure. Azure Container Apps continues to use the in-memory limiter (no Redis deployed on Azure).

* **Atomic Lua Script with `PEXPIRE ... NX` (Redis 7+)**:
  * *Single Round-Trip Atomicity*: Rather than separate `INCR` and `EXPIRE` commands (which risk orphan unexpiring keys if the process crashes between commands, or race conditions if concurrent threads attempt to initialize expiry), the rate-limiting logic is executed in a single atomic Lua script:
    ```lua
    local count = redis.call('INCR', KEYS[1])
    redis.call('PEXPIRE', KEYS[1], ARGV[1], 'NX')
    return { count, redis.call('PTTL', KEYS[1]) }
    ```
  * *Why `NX` (Redis 7.0+)*: The `NX` option on `PEXPIRE` sets the expiry only if the key has no existing expiration. This elegantly handles two critical conditions in a single unified branch:
    1. The first request initializes the key and sets its TTL to the configured window.
    2. Any subsequent request leaves the already-running TTL untouched, preserving the fixed window expiration.
    3. If a previous key somehow lost its expiry (or after recovery from an incomplete write), `NX` safely re-attaches the window without resetting an active window.
  * *Atomic Return*: The script returns both `count` and `PTTL` in the same operation, allowing the Java application to evaluate `count <= limit` and compute the exact remaining seconds for the `Retry-After` header without an extra round trip.

* **Redis-Owned Time vs Clock-Aligned Minute Windows**:
  * *Window Semantics*:
    * `InMemoryFixedWindowRateLimiter` calculates window index based on the host clock aligned to wall-clock minutes (`clock.millis() / windowMillis`).
    * `RedisFixedWindowRateLimiter` starts a client's window upon arrival of their *first request* (key creation + `PEXPIRE`), and the window ends when Redis automatically expires the key.
  * *Clock Independence*: Time is governed entirely by Redis's internal timer. Application host clock drifts or backward NTP adjustments have zero effect on rate limiting, completely eliminating the backward-clock busy-spin risk identified in Step A.
  * *Retry Calculation*: `PTTL` provides remaining milliseconds until key expiry. The Java layer calculates `retryAfter = Math.ceil(pttl / 1000.0)` clamped to `[1 s, window]`, safely handling sub-second expirations and negative PTTL edge cases (-1 no ttl, -2 key expired).

* **Fail-Open Strategy and Availability Trade-offs**:
  * *Fail-Open Behavior*: If Redis is down, times out, or throws a `DataAccessException`, `RedisFixedWindowRateLimiter` logs a single warning and returns `RateLimitDecision.Allowed()`.
  * *Availability vs Strict Limiting*: Under infrastructure degradation, URL creation availability is prioritized over strict abuse prevention. Rejecting legitimate users because Redis is unreachable is unacceptable when PostgreSQL remains healthy.
  * *Privacy Protection*: The fail-open log statement logs only the exception message (e.g. `Unable to connect to Redis`) and never logs the client IP address (`clientKey`), protecting user privacy and PII in production log streams.

* **Unconditional Lettuce Fail-Fast Customizer**:
  * The Lettuce `ClientOptions` customizer (`disconnectedBehavior(REJECT_COMMANDS)` and command timeout matching `shortener.cache.timeout`) was decoupled from `shortener.cache.enabled` and made unconditional in `CacheConfiguration`.
  * *Rationale*: When `shortener.rate-limit.store=redis` is used without caching, Lettuce must fail fast immediately when Redis is unreachable. Without fail-fast client options, commands would buffer indefinitely or block HTTP worker threads up to default timeouts (60 seconds), degrading application throughput during Redis outages.

* **Configuration and Type-Safe Binding**:
  * Controlled by `shortener.rate-limit.store`:
    * Enum values: `MEMORY` (default), `REDIS`.
    * Default is `MEMORY` via `@DefaultValue("MEMORY")` and `@ConditionalOnProperty(matchIfMissing = true)`.
    * Case-insensitive matching: Spring Boot's `@ConditionalOnProperty` evaluates `havingValue.equalsIgnoreCase(propertyValue)`, accepting `memory`, `MEMORY`, `redis`, or `REDIS`.
    * Type-safety: If an invalid value (e.g. `shortener.rate-limit.store=memcached`) is configured, Spring Boot fails fast during startup with a `ConfigurationPropertiesBindException`.

---

## 15. Stage 4: Code Generation Strategies & Feistel Permutation Decisions (Step A)

* **Strategy Comparison & Architectural Decision**:
  * Four primary code generation strategies were evaluated:
    | Strategy | Gains | Costs & Trade-offs | Decision |
    | :--- | :--- | :--- | :--- |
    | **Random** (`SecureRandom` + Base62) | Zero coordination; no database round-trip before insert; uniform distribution across the code space. | Collisions increase with table size due to birthday paradox; requires retry loop and unique constraint. | **DEFAULT (Azure unchanged)**. Simple, stateless, perfectly suited for low-to-medium scale. |
    | **Counter / Sequence** (Postgres `nextval` + Bijective Permutation + Base62) | Mathematical guarantee of zero collisions among sequence codes; predictable sequential allocation. | Extra database round trip per create (`nextval`); sequential codes require scrambling to prevent enumeration; sequence gaps on rollback. | **AVAILABLE VIA CONFIG** (`shortener.code.strategy=sequence`). |
    | **Hash Truncation** (e.g. SHA-256 / MD5 truncated to 7 chars) | Deterministic mapping; identical URLs can map to identical codes (deduplication). | Truncating a 256-bit hash to 42 bits brings birthday collisions right back; reveals whether two users submitted the same target URL; URL deduplication is a controversial product decision (ownership, analytics, deletion semantics). | **DOCUMENTED ONLY**. Rejected for active generation. |
    | **Snowflake ID** (Timestamp + Worker ID + Sequence) | Decentralized, monotonic, coordination-free across multiple workers. | Requires ~64 bits ($\approx 11$ Base62 chars, exceeding the 7-char budget); tightly coupled to worker clock synchronization (NTP drift / backward steps cause errors); massive architectural overkill for a single replica (`max_replicas = 1`). | **DOCUMENTED ONLY**. Rejected. |
  * *Decision*: `random` remains the default strategy deployed to Azure. `sequence` is provided as an alternative strategy toggled via configuration (`shortener.code.strategy=sequence`).

* **Collision Mathematics & Birthday Paradox (COMPUTED)**:
  * For $M = 62^7 = 3{,}521{,}614{,}606{,}208$ available 7-character Base62 slots and $n$ existing rows:
    * **Next-code collision probability**: $P(\text{collision}) = \frac{n}{M}$
    * **At least one collision in table**: $P(\ge 1\text{ collision}) \approx 1 - e^{-\frac{n^2}{2M}}$ (Birthday problem)
    * **Exhaustion of all 3 creation attempts**: $P(\text{all 3 fail}) = \left(\frac{n}{M}\right)^3$
  * Computed probabilities across dataset sizes:
    | Rows ($n$) | $P(\text{next code collides})$ | $P(\ge 1\text{ collision in table})$ | $P(\text{all 3 retry attempts fail})$ |
    | :--- | :--- | :--- | :--- |
    | $1{,}000$ | $2.8 \times 10^{-10}$ | $\approx 0$ ($1.4 \times 10^{-7}$) | $2.3 \times 10^{-29}$ |
    | $100{,}000$ | $2.8 \times 10^{-8}$ | $0.14\%$ ($1.4 \times 10^{-3}$) | $2.3 \times 10^{-23}$ |
    | $1{,}000{,}000$ | $2.8 \times 10^{-7}$ | $13.2\%$ ($0.132$) | $2.3 \times 10^{-20}$ |
    | $10{,}000{,}000$ | $2.8 \times 10^{-6}$ | $\approx 100\%$ ($1 - 1.2 \times 10^{-6}$) | $2.3 \times 10^{-17}$ |
  * *Conclusion*: Collisions become inevitable in the overall dataset long before the total address space fills (birthday paradox at $\sim 10^6$ rows). However, the probability of any single request failing all 3 retries remains vanishingly small ($< 10^{-16}$ even at 10 million URLs), proving that the 3-attempt retry loop makes random generation robust and harmless in production.

* **Production Verification on Neon PostgreSQL (MEASURED)**:
  * *Measurement Date*: 2026-11-01.
  * *Live Database State*: Neon production database contains 73 rows in `short_url`.
  * *Sequence Status*: `short_code_seq` query `SELECT last_value, is_called FROM short_code_seq` confirms `is_called = false`. The sequence exists via Flyway migration V2 but has never been called because Azure runs the default `RANDOM` strategy.
  * *Collision Probability*: At $n = 73$, next-code collision probability is $73 / 3{,}521{,}614{,}606{,}208 \approx 2.07 \times 10^{-11} \approx 2\text{e-}11$, verifying Hypothesis 3 at demo scale.
  * *Flyway V2 Deployment Verification*: MEASURED 2026-10-31: Flyway V2 was successfully applied on Neon at deploy time (schema version 2 confirmed in Azure Container Apps logs for image `cb6290a`).

* **Rejected Design: Affine Scramble (Honesty Rule)**:
  * *Initial Proposal*: Linear congruential bijection $y = (x \cdot A + B) \bmod M$, with coprime multiplier $A$ and offset $B$.
  * *Vulnerability Caught in Review*: For consecutive sequence values $x_1$ and $x_2 = x_1 + 1$:
    $$\Delta y = (y_2 - y_1) \equiv A \pmod M$$
    Because the step is constant, an attacker observing just two consecutively issued links immediately recovers multiplier $A$. They can then enumerate adjacent codes ($y \pm kA \pmod M$) without even knowing $B$.
  * *Outcome*: Caught during review prior to implementation; rejected in favor of a keyed pseudo-random permutation.

* **Keyed Feistel Cipher Design & Cycle-Walking**:
  * *Domain*: $M = 62^7 = 3{,}521{,}614{,}606{,}208 < 2^{42} = 4{,}398{,}046{,}511{,}104$.
  * *Structure*: A balanced 42-bit Feistel network split into two 21-bit halves ($L, R \in [0, 2^{21}-1]$), executed over 4 rounds.
  * *Round Function*: HMAC-SHA256 (standard `javax.crypto.Mac`, pure JDK) keyed by `shortener.code.secret`. The round number $i \in \{0, 1, 2, 3\}$ and right half $R$ are fed into the HMAC, and the 256-bit digest is truncated to 21 bits ($F(R, i) = \text{HMAC}(K, i \parallel R) \bmod 2^{21}$).
  * *Cycle-Walking*: Because $2^{42} > M$, the 42-bit permutation can output a value $y \ge M$. Cycle-walking reapplies the permutation repeatedly until $y < M$. Because any bijection on a finite set decomposes into disjoint permutation cycles, cycle-walking is mathematically guaranteed to terminate and preserves a perfect uniform bijection over $[0, M-1]$.
  * *Cycle-Walking Iterations (MEASURED vs THEORY)*:
    * *Theoretical Expectation*: $\frac{2^{42}}{M} = \frac{4{,}398{,}046{,}511{,}104}{3{,}521{,}614{,}606{,}208} \approx 1.24887$.
    * *Empirical Measurement*: Over 1,000,000 consecutive inputs, the Feistel cipher required exactly 1,248,091 total Feistel evaluations, yielding an average of **1.248 iterations** per input (matching theory within 0.07%).
  * *Cryptographic Limits*:
    * This is an obfuscation mechanism designed to eliminate enumerable codes; it is **not** a NIST-standardized format-preserving encryption (FPE) scheme such as FF1 or FF3-1.
    * The default secret in `application.yml` is publicly visible in version control; development codes are not confidential. Production deployments must configure an unguessable private secret via `SHORTENER_CODE_SECRET`.

* **Implementation Notes & Operational Trade-offs**:
  * *Thread Safety*: `javax.crypto.Mac` instances are mutable and not thread-safe. A `ThreadLocal<Mac>` is maintained in `FeistelCipher` to avoid synchronization bottlenecks while eliminating per-call Mac initialization overhead.
  * *Non-Transactional Sequences*: PostgreSQL sequences are non-transactional: calling `nextval` immediately advances the sequence and cannot be rolled back. If an insert fails (e.g. timeout, conflict), sequence numbers are lost, leaving harmless gaps in the counter space.
  * *Latency Impact*: The `sequence` strategy requires an additional synchronous database round trip per create request (`SELECT nextval('short_code_seq')` before `INSERT`), unlike `random` which performs zero database queries before the insert.
  * *Retention of Retry Loop & Unique Constraint*: Both the unique constraint on `short_url.code` and the 3-attempt retry loop in `ShortUrlService` are retained for both strategies. If an operator switches strategies on a live database or rotates `shortener.code.secret`, sequence-generated codes can collide with previously persisted random codes.

---

## 16. Stage 5: Click Analytics & Background Buffer Decisions (Step A & Step B)

* **Problem Statement & Architectural Tension**:
  * A naive implementation of click tracking records every redirect by performing a synchronous `INSERT` or `UPDATE` into PostgreSQL on the HTTP request thread.
  * In a serverless cloud environment (such as Azure Container Apps communicating with Neon PostgreSQL), every database write incurs a network round-trip ($\approx 20\text{--}50\text{ ms}$ or several seconds during Neon cold wake-up) directly on the redirect critical path.
  * Furthermore, synchronous writes couple the availability of the redirect service to the health of the database: if PostgreSQL experiences latency spikes, connection pool starvation, or brief downtime, redirects fail or time out.

* **Architectural Options & Decision Table**:
  | Architecture | Delivery Semantics | Operational Cost & Infrastructure | Latency Impact on Redirect | Decision |
  | :--- | :--- | :--- | :--- | :--- |
  | **In-Process Bounded Buffer** (`ConcurrentHashMap` + background flusher) | At-most-once (buffered clicks can be lost on ungraceful crash or failed batch) | **\$0.00**. Uses existing JVM heap memory; requires zero additional cloud services or daemons on Azure Container Apps. | **Zero I/O overhead** ($O(1)$ lock-free `LongAdder.increment()` on hot path; non-blocking, never throws). | **ACCEPTED**. Default implementation for Azure and local demo. Free, self-contained, and preserves independent redirect availability. |
  | **Redis Streams / PubSub** | At-least-once / At-most-once | Requires provisioning managed Redis in Azure (costing \$15–\$50+/month) or maintaining Redis cluster infrastructure. | Incurs network round trip to Redis ($\approx 1\text{--}2\text{ ms}$) on redirect path. | **REJECTED**. Avoids cloud hosting costs for a portfolio demo project. |
  | **Apache Kafka / Pulsar** | At-least-once / Exactly-once (with transactional producers & consumer offset commits) | Heavy infrastructure footprint (Kafka brokers, Schema Registry, ZooKeeper/KRaft); significant compute and memory overhead. | Asynchronous producer send with local buffering, but high client library footprint. | **REJECTED**. Kafka architectural patterns were already implemented and demonstrated in a previous project; unnecessary overhead for this service. |

* **Delivery Semantics: At-Most-Once vs Exactly-Once**:
  * *Why At-Most-Once is Acceptable for Analytics*: Click metrics are aggregate, non-financial operational approximations. A business decision or traffic chart does not change if a crash drops 5 clicks out of 10,000. In exchange for at-most-once delivery, the redirect hot path receives complete isolation from database I/O, zero latency overhead, and immunity from database downtime.
  * *What Would Change for Monetary Transactions*: If click events represented billing events or monetary charges (e.g. pay-per-click advertising), at-most-once loss would be unacceptable. A financial architecture would require:
    1. **Transactional Outbox / Two-Phase Commit**: Storing event records atomically within the same database transaction as the business entity, or using Change Data Capture (CDC, e.g. Debezium) off the Postgres WAL.
    2. **Idempotency Keys & Deduplication**: Unique event identifiers per transaction to prevent double counting across retries.
    3. **Durable Distributed Log**: Publishing to Apache Kafka with `acks=all` and transactional producers, consumed by stream processors (e.g. Apache Flink) tracking consumer offsets.
  * *Exact Loss Cases in the In-Process Design*:
    1. **Ungraceful Termination**: Process killed abruptly without `SIGTERM` (`SIGKILL` / `kill -9`, power loss, or sudden VM termination) loses un-flushed clicks in the active in-memory buffer.
    2. **Failed Batch Write**: If a database batch upsert fails (e.g. connection drop, database outage, or foreign key violation), the entire batch is dropped without retry to guarantee that partial retries never cause double counting.
    3. **Atomic Swap Race**: When the flusher atomically swaps `activeMapRef`, a concurrent redirect thread that already resolved the old map reference may execute `LongAdder.increment()` after or during the flusher's drain/iteration. Because the hot path is non-blocking and lock-free, this click is recorded in the discarded map and lost.
    4. **Capacity Bound Shedding**: When distinct active keys in memory reach `max-pending-keys` (default 10,000), attempts to allocate new keys are dropped and tallied in a drop counter to bound memory footprint.

* **Backpressure & Memory Bounds**:
  * *Bounded Pending Keys*: Bounded by `shortener.analytics.max-pending-keys` (default 10,000 distinct `(code, day)` entries).
  * *Drop-and-Count Strategy*: Clicks on existing keys in the map always succeed. When the map size reaches the limit, new keys are rejected, and the drop count is incremented via an atomic counter (`droppedClicks`).
  * *Summary Logging*: To prevent log saturation, rejected clicks do not log individual warnings. Instead, the background flusher emits a single summary warning per flush if any clicks were dropped: `"Dropped {} clicks since last flush"`.

* **Storage Model & GDPR Privacy Compliance**:
  * *Table Schema*:
    ```sql
    create table click_stats (
        code varchar(16) not null references short_url(code),
        day date not null,
        count bigint not null,
        primary key (code, day)
    );
    ```
  * *Zero Personal Data*: Stores strictly `(code, day, count)`. No IP addresses, user-agent strings, cookies, or HTTP referrers are stored. Under EU GDPR (General Data Protection Regulation), IP addresses are legally classified as personal data; aggregating strictly by date and short code eliminates personal data storage and compliance burdens entirely.

* **Correctness & Reliability Details**:
  * *ScheduledExecutorService Task Suppression Prevention*: In Java's `ScheduledExecutorService`, if a periodic task throws an unchecked exception, the executor suppresses all subsequent scheduled executions silently. The flush task wraps execution in a top-level `catch (Throwable t)` block, logging a single WARN line and preserving the periodic flusher.
  * *Single Transaction Per Batch Upsert*: `ClickStatsWriter` executes batches inside `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. In PostgreSQL default auto-commit, statements commit individually; wrapping the batch ensures all-or-nothing atomicity.
  * *Foreign Key Violation Handling*: If a row references a deleted or nonexistent short code, the entire batch rolls back and is dropped, preventing inconsistent partial state.
  * *Post-Shutdown Invocation Safety*: If `record(code)` is called after `stop()` has completed, it checks `running.get()` and safely no-ops without throwing `RejectedExecutionException`.

* **Snapshot Consistency for Stats Queries**:
  * *Transactional Isolation*: `ClickStatsReader.getStats(code)` is annotated with `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`.
  * *Why READ COMMITTED is Insufficient*: PostgreSQL's default `READ COMMITTED` isolation acquires a fresh MVCC snapshot for each separate SQL statement. If a background flusher commits a batch between the total clicks query (`SELECT SUM(count) ...`) and the daily slice query (`SELECT day, count ... LIMIT 30`), `totalClicks` would diverge from the sum of the returned `daily` records. Under `REPEATABLE READ`, PostgreSQL freezes an MVCC snapshot at the start of the first query in the transaction, ensuring that `totalClicks` and `daily` rows are computed from an identical, self-consistent database state.
  * *Proxy Enforcement*: `ClickStatsController` injects `ClickStatsReader` as a Spring-managed bean, ensuring all invocations traverse the Spring AOP transaction proxy without self-invocation bypass.

* **Graceful Shutdown & Spring Lifecycle Ordering**:
  * Spring Boot 4.1.1 manages graceful shutdown using `SmartLifecycle` beans ordered by phase.
  * Quoted source phases:
    * `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE = 2147482623` (`Integer.MAX_VALUE - 1024`): Initiates web server graceful shutdown, rejecting new HTTP connections and finishing in-flight requests.
    * `WebServerStartStopLifecycle`: Phase `2147481599` (`Integer.MAX_VALUE - 2048`): Stops the embedded Tomcat web server.
    * `BufferedClickRecorder`: Configured with phase `2147480575` (`Integer.MAX_VALUE - 3072`): Stops accepting clicks, runs a final flush of buffered counts to PostgreSQL, and terminates its executor thread.
    * `DataSource` closure: Occurs subsequently in `AbstractApplicationContext.destroyBeans()`, strictly after all `SmartLifecycle` beans have stopped.
  * Because Spring's `DefaultLifecycleProcessor` stops beans in reverse phase order, the shutdown order is strictly guaranteed:
    1. Tomcat stops accepting incoming HTTP requests ($2{,}147{,}482{,}623$).
    2. Web server stops ($2{,}147{,}481{,}599$).
    3. `BufferedClickRecorder` flushes remaining in-memory counts to Postgres and shuts down executor ($2{,}147{,}480{,}575$).
    4. HikariCP connection pool is closed during bean destruction.
  * *Shutdown Flush Verification (MEASURED)*:
    * In `ShutdownFlushTest`, periodic flushing was disabled by setting a 1-hour interval. 3 redirects were recorded (0 rows in `click_stats`). Calling `context.close()` triggered graceful shutdown, resulting in exactly 3 rows in `click_stats` upon completion.
    * Local SIGTERM verification: 5 initial clicks flushed; 3 additional clicks recorded and terminated via `docker compose stop` (`SIGTERM`). Verification showed count updated from 5 to 8. (Supporting observation only: periodic flush could not be completely excluded during container stop).

* **Cloud Persistence Verification (MEASURED)**:
  * *Neon Migration V3*: MEASURED 2026-11-01 13:08 UTC: Flyway migration `V3__create_click_stats.sql` applied successfully on Neon PostgreSQL during deployment.

* **Redirect Latency Benchmark: Analytics Enabled vs Disabled (MEASURED)**:
  * Tested locally on Docker Compose PostgreSQL with cache disabled (`shortener.cache.enabled=false`), 1 warm-up request followed by $n=10$ requests measuring `time_total` via curl on the same code:
  * **a. Analytics Enabled (`BufferedClickRecorder`, defaults)**:
    * Raw (s): `0.012903, 0.009986, 0.009516, 0.009491, 0.009588, 0.007806, 0.007983, 0.008508, 0.008026, 0.008425`
    * Summary: Min = **7.81 ms**, Median = **9.00 ms**, Max = **12.90 ms**
  * **b. Analytics Disabled (`NoOpClickRecorder`, `shortener.analytics.enabled=false`)**:
    * Raw (s): `0.014568, 0.014805, 0.009021, 0.015353, 0.013383, 0.009569, 0.010231, 0.008893, 0.014847, 0.012207`
    * Summary: Min = **8.89 ms**, Median = **12.80 ms**, Max = **15.35 ms**
  * *Honest Interpretation*:
    * The measured ranges completely overlap (7.81–12.90 ms enabled vs 8.89–15.35 ms disabled), reflecting normal CPU/OS scheduling jitter and local loopback TCP latency.
    * As intended by design, `BufferedClickRecorder.record()` performs a non-blocking $O(1)$ `LongAdder.increment()` on an in-memory map without issuing network or disk I/O on the redirect thread. There is **no measurable latency degradation** introduced by enabling click analytics.

* **Live Azure Restart Experiment**:
  * **DONE (2026-11-02)**: shutdown flush verified on Azure Container Apps during a revision restart right after a burst of 20 clicks (not under concurrent load: no requests were in flight during shutdown). See "Stage 5: live verification on Azure" below.

### Stage 5: live verification on Azure (MEASURED 2026-11-02)

* **Shutdown flush survives a restart in production.** Revision `ca-shortener--0000017`
  (created 15:48:17 UTC) ran with `SHORTENER_ANALYTICS_FLUSH_INTERVAL=1h` and
  `SHORTENER_ANALYTICS_FLUSH_THRESHOLD=100000`, so neither periodic nor threshold flushes could run.
  Stats for `uibkYXU`: 3 before, still 3 after 20 redirects (clicks only in memory), revision
  restarted at 15:49:41 UTC, then 23 (20 dated 2026-11-02). Only the SmartLifecycle shutdown flush
  could have written them: Container Apps sends SIGTERM with enough grace time for the final flush.
  Assumption: single-revision mode had switched traffic to revision 17 (created ~1 min before the clicks).
* **Operations note (mistake, kept per the honesty rule).** The temporary Tomcat access log enabled on
  2026-10-31 for the client-IP investigation (it printed client IPs to stdout) was not removed: the
  cleanup command was never run, and each deploy copied the environment forward (revisions up to
  `0000018`). Found and removed on 2026-11-02 (revision `0000019`). Lesson: a temporary production
  setting needs a removal *check* (list the env vars afterwards), not just a removal command.

---

## 17. Stage 7: Frontend Architecture & CORS Servlet Filter Decisions (Step A1)
* **CORS Placement: Servlet-Level Filter vs Spring MVC**:
  * *Decision*: Configure CORS as a servlet-level `FilterRegistrationBean<CorsFilter>` (`Ordered.HIGHEST_PRECEDENCE`), mapped to `/api/*` and ordered strictly before `RateLimitFilter`. In Spring Boot 4.1, `@Order` on a `@Bean` factory method returning a raw `Filter` is not honored by `ServletContextInitializerBeans` unless `@FilterRegistration` is present, making `FilterRegistrationBean` mandatory for reliable filter ordering.
  * *Why WebMvcConfigurer is Insufficient*: `RateLimitFilter` operates in the servlet filter chain before requests reach Spring MVC's `DispatcherServlet`. When a client exceeds the rate limit, `RateLimitFilter` writes the RFC 9457 HTTP 429 ProblemDetail payload directly to the servlet response stream and short-circuits the chain. If CORS were registered via Spring MVC (`WebMvcConfigurer.addCorsMappings()`), MVC CORS interceptors would never execute on 429 rejections. The HTTP 429 response would leave without `Access-Control-Allow-Origin`, causing the browser to block the response and hide the 429 status and `Retry-After` header from the frontend SPA. Placing `CorsFilter` at the head of the servlet filter chain ensures all responses—including 429 rate-limit rejections—carry proper CORS headers and exposed headers (`Location`, `Retry-After`).
  * *Configuration*: Path `/api/**`, allowed origins from `shortener.cors.allowed-origins` (`${CORS_ALLOWED_ORIGINS:http://localhost:4200}`), methods `GET, POST, OPTIONS`, allowed headers `Content-Type`, exposed headers `Location, Retry-After`, max age 3600 s.
  * *Preflight Rate Limit Exemption*: `RateLimitFilter.shouldNotFilter()` explicitly checks `!("POST".equalsIgnoreCase(request.getMethod()) && "/api/urls".equals(request.getRequestURI()))`. HTTP `OPTIONS` preflight requests evaluate to `false` for the POST condition, bypassing the rate limiter filter entirely and consuming 0 tokens.
  * *MEASURED (2026-11-02)*: Live from Azure Cloud Shell (single IP), 10 x 201 then 429 with `access-control-allow-origin`, `access-control-expose-headers` and `retry-after` -> `CorsFilter` runs before `RateLimitFilter` on real Tomcat behind the ingress. From the home connection, two quick runs of 25 POSTs (50 total; the second run within one minute, the first untimed) produced no 429 -> that connection likely uses at least three egress IPs (inferred, not observed directly).

---

## 18. Stage 7: Angular Frontend Architecture (Step A2)

* **Architecture: Zoneless Standalone Components**:
  * *Decision*: Use Angular 22 standalone components with zoneless change detection (`provideZonelessChangeDetection()`) and signals-first reactivity (`signal`, `computed`, `effect`), without `NgModule` or `@angular/router`.
  * *Reasoning*: The frontend is a focused single-page utility with two cohesive cards (Shorten and Stats). Eliminating `zone.js` reduces initial bundle size, improves runtime performance, and aligns with modern Angular best practices. Omitting client-side routing eliminates route resolution complexity and prevents collisions with the backend's top-level redirect pattern `/{code}`.

* **API Client & RFC 9457 Error Mapping**:
  * *Decision*: Centralize API communication in `UrlApiService` and error normalization in a pure `mapHttpError` function.
  * *Reasoning*: The Spring Boot backend adheres strictly to RFC 9457 (`application/problem+json`). `mapHttpError` provides type-safe mapping:
    * `status === 0`: Detects network disconnections or CORS preflight failures and returns `"Cannot reach the server (network or CORS)"`.
    * `status === 400`: Maps field-level validation errors from `errors: [{field, message}]` into distinct UI bullet items.
    * `status === 404`: Displays resource not found details.
    * `status === 429`: Extracts the CORS-exposed `Retry-After` header (falling back to 60 s if missing/invalid) to trigger UI cooldown countdowns.

* **Cold-Start UX**:
  * *Decision*: Display `ColdStartBannerComponent` if any API request remains in-flight after 2.5 seconds, and hide it immediately upon response completion.
  * *Reasoning*: Azure Container Apps scales to zero when idle; cold starts can take ~30 s while the platform provisions container resources before JVM initialization. Providing clear, non-blocking feedback (*"Waking up the backend (it scales to zero; the first request can take ~30 s)"*) sets user expectations without artificial timeouts or premature failure states.

* **HTTP 429 Rate-Limit Countdown**:
  * *Decision*: When creation is rejected with HTTP 429, initiate a 1-second interval countdown from `retryAfterSeconds` that disables the shorten submission button until it reaches 0.
  * *Reasoning*: Prevents spamming a blocked endpoint, provides visible cooldown progress to the user, and cleans up active timers upon component destruction (`DestroyRef.onDestroy`).

---

## 19. Stage 7: Static Web Apps Hosting (Step A3)

* **Hosting: Azure Static Web Apps, Free SKU**:
  * *Decision*: Serve the prebuilt Angular SPA (`frontend/dist/frontend/browser`, the `@angular/build:application` output folder, confirmed by `ls` of a real build) from `swa-shortener` on the Free plan.
  * *Reasoning*: The Free plan has no overage billing. Past the bandwidth quota it stops serving instead of billing:
    * Quota page (https://learn.microsoft.com/en-us/azure/static-web-apps/quotas): "Included bandwidth (per month) | 100 GB" and "Overage bandwidth | Unavailable" (Free plan column).
    * Pricing FAQ (https://azure.microsoft.com/en-us/pricing/details/app-service/static/): "In the free plan if your site exceeds the 100 GB quota, we will not be able to serve your site. Standard apps that exceed the 100 GB standard quota will be billed at overage rates."
  * *Deploy*: The `deploy` job in `frontend.yml` runs only on push to `main`, after `build-and-test`. It downloads the tested build artifact and uploads it with `Azure/static-web-apps-deploy@v1` (`action: upload`, `skip_app_build: true`, `output_location: ''`, no API). It has no checkout, so the bytes that passed the tests are the bytes deployed. It has `permissions: {}`: the only credential is the deployment token secret, and `repo_token` is omitted ("Currently used only for commenting on Pull Requests"). No PR preview environments.

* **Region refusal and fallback (MEASURED 2026-11-02)**:
  * `az staticwebapp create … --location westeurope` was refused: "The selected region is currently not accepting new customers". The resource was created in `eastus2` instead. `Microsoft.Web` had to be registered first (it was `NotRegistered`).
  * *Why the fallback is acceptable*: The SWA region only holds the resource metadata; the static content is distributed globally, so `eastus2` does not put the files far from European users.

* **CORS: production origin only (MEASURED 2026-11-02)**:
  * `CORS_ALLOWED_ORIGINS=https://gentle-island-020fcf90f.3.azurestaticapps.net` on `ca-shortener`. `http://localhost:4200` remains only the local default in `application.yml` and is no longer allowed in production.
  * Preflight (`OPTIONS /api/urls`) from the SWA origin returned **200**; from `http://localhost:4200` it returned **403**.

* **Hosting note (honest)**: The Azure free trial ends ~2026-11-05. The owner has decided not to upgrade for now, so the live demo (backend on Container Apps and frontend on Static Web Apps) is expected to stop working then. The code, workflows and this record stay on GitHub.

* **Live end-to-end check after deploy**: **TO DO**. Not yet done: a first deploy run on `main`, then shorten and stats from the SWA URL in a browser against the live backend.
