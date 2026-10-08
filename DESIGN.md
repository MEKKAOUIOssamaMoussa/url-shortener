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

---

## 5. Open Questions

* What is the p95 and p99 redirect latency under scale-to-zero vs. keeping 1 minimum replica active?
* Will Neon serverless connection latency require connection pooling adjustments (e.g., HikariCP sizing or PgBouncer) during burst traffic?
* At what scale does caching (Redis) become mandatory to protect Neon compute hours?
* What makes up the ~24 s of cold start outside Spring, and is a smaller image worth it?
