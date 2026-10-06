# System Design & Architecture Decisions

> **Status**: First Draft (Stage 0). This document captures early architecture choices and testable hypotheses that will be refined and revised with empirical measurements as development progresses.

---

## 1. Goal
Build a high-performance, cost-effective URL shortener web application capable of handling link redirection, analytics, and custom alias management. The project is implemented in iterative stages starting from a walking skeleton to a complete multi-tier system with modern cloud deployment.

---

## 2. Stage 0 Architecture Decisions

* **Compute Platform**: **Azure Container Apps (ACA)** on the Consumption plan with `minReplicas = 0`.
  * *Rationale*: Scales to zero when idle, fitting within Azure's free grant (180,000 vCPU-seconds and 360,000 GiB-seconds per month) to minimize operational costs for a portfolio project.
* **Container Registry**: **GitHub Container Registry (GHCR)** (`ghcr.io`).
  * *Rationale*: Co-located with GitHub Actions CI/CD workflows, providing integrated authentication, fast layer uploads, and generous free-tier storage.
* **CI/CD & Cloud Auth**: **GitHub Actions with OpenID Connect (OIDC)** to Azure.
  * *Rationale*: Eliminates long-lived service principal secrets in repository settings; uses federated credentials for secure token exchange.
* **Database (Stage 1+)**: **PostgreSQL on Neon Serverless Postgres**.
  * *Rationale*: Serverless compute branch-per-environment architecture, fast auto-suspend, and instant scale-up with zero idle compute cost.
* **Local Development Stack**: **Local Docker Compose** (introduced in Stage 1).
  * *Rationale*: Emulates the complete target topology locally (backend, Postgres, and future Redis/caching layer) to provide high-fidelity local feedback before cloud deployment.

---

## 3. Hypotheses to Measure Later

1. **Cold Start Latency**:
   * *Hypothesis*: Java 21 + Spring Boot 4.x running in ACA with scale-to-zero will experience an initial cold start latency of 3–6 seconds upon first request after idle suspension. We will evaluate startup optimizations (e.g., CDS, JVM tuning, or native compilation) in later stages if cold start exceeds SLAs.
2. **Operational Cost**:
   * *Hypothesis*: Steady-state monthly cloud expenditure will remain near \$0.00 by remaining within ACA's free tier consumption grant and Neon's serverless free tier.

---

## 4. Open Questions

* What is the p95 and p99 redirect latency under scale-to-zero vs. keeping 1 minimum replica active?
* Will Neon serverless connection latency require connection pooling adjustments (e.g., HikariCP sizing or PgBouncer) during burst traffic?
* At what scale does caching (Redis) become mandatory to protect Neon compute hours?
