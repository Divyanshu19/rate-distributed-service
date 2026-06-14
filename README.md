# Distributed Rate Limiter Service

A production-grade, horizontally scalable rate limiting service built with **Java 21**, **Spring Boot 3**, and **Redis**.
Designed from the ground up for multi-tenant, per-endpoint request throttling with pluggable algorithms.

---

## Why This Exists

Every public-facing API needs rate limiting. The naive approach — an in-memory counter per JVM — breaks the moment you scale to more than one instance.
This service solves that by centralising all counters in Redis, making the limit enforced consistently across every node in your cluster.

---

## How It Works

```
Incoming Request
      │
      ▼
 HTTP Filter / Interceptor
      │   extracts tenantId + endpoint
      ▼
 RateLimiterService
      │   looks up RateLimitRule for (tenantId, endpoint)
      │   runs algorithm against Redis
      ▼
 ┌──────────────┐      ┌──────────────────────┐
 │  ALLOW (2xx) │  or  │  REJECT (429 Too Many │
 │              │      │  Requests)            │
 └──────────────┘      └──────────────────────┘
```

All counters and timestamps live in **Redis**. The app itself is stateless — you can run N instances without coordination.

---

## Supported Algorithms

| Algorithm       | How it works                                                                  | Best for                          |
|-----------------|-------------------------------------------------------------------------------|-----------------------------------|
| `SLIDING_WINDOW`| Tracks exact request timestamps in a Redis Sorted Set. Evicts stale entries.  | Accurate limiting, no edge bursts |
| `TOKEN_BUCKET`  | Bucket refills at a fixed rate. Each request consumes one token.              | Allowing short, bursty traffic    |

---

## Rate Limit Rule

The core data model is `RateLimitRule`:

```java
RateLimitRule rule = RateLimitRule.builder()
    .tenantId("tenant-acme")          // which client
    .endpoint("/api/v1/orders")        // which endpoint
    .maxRequests(100)                  // max requests allowed
    .windowSizeSeconds(60)             // within this window
    .algorithm(Algorithm.SLIDING_WINDOW)
    .build();
```

**Redis key format:** `rate_limit:{tenantId}:{endpoint}`
**Example key:** `rate_limit:tenant-acme:/api/v1/orders`

---

## Tech Stack

| Layer        | Technology                          |
|--------------|-------------------------------------|
| Runtime      | Java 21, Spring Boot 3.5            |
| Data Store   | Redis 7.2 (via Lettuce client)      |
| Algorithms   | Sliding Window, Token Bucket        |
| Resilience   | Resilience4j (Day 5)                |
| Metrics      | Micrometer + Prometheus (Day 6)     |
| Load Testing | Gatling (Day 8)                     |
| Deployment   | Docker + Kubernetes (Day 9)         |

---

## Running Locally

**Prerequisites:** Docker, Java 21, Maven 3.9+

```bash
# 1. Start Redis
docker compose up -d

# 2. Verify Redis is healthy
docker compose exec redis redis-cli ping
# Expected: PONG

# 3. Start the application
./mvnw spring-boot:run

# 4. Check health (should show Redis: UP)
curl http://localhost:8080/actuator/health
```

---

## Project Structure

```
src/main/java/com/ratelimiter/distributed/
├── core/
│   ├── model/
│   │   ├── RateLimitRule.java     ← Domain model
│   │   └── Algorithm.java         ← SLIDING_WINDOW | TOKEN_BUCKET
│   └── service/                   ← (Day 2) Rate limiter logic
├── api/                           ← (Day 3) REST controllers + filters
├── config/
│   └── RedisConfig.java           ← Redis wiring
└── metrics/                       ← (Day 6) Micrometer metrics
```

---

## 10-Day Build Plan

| Day | Goal                                                          | Status  |
|-----|---------------------------------------------------------------|---------|
| 1   | Project skeleton, domain model, Redis connection              | ✅ Done |
| 2   | Sliding Window algorithm implementation + unit tests          | ⬜      |
| 3   | Token Bucket algorithm + REST API layer                       | ⬜      |
| 4   | Per-tenant rule loading from Redis + integration tests        | ⬜      |
| 5   | Resilience4j circuit breaker around Redis calls               | ⬜      |
| 6   | Micrometer metrics + Prometheus scrape endpoint               | ⬜      |
| 7   | Redis Sentinel / Cluster configuration                        | ⬜      |
| 8   | Gatling load tests — validate limits hold under pressure      | ⬜      |
| 9   | Dockerfile + Kubernetes manifests (Deployment, Service, HPA)  | ⬜      |
| 10  | End-to-end smoke test in local k8s cluster (kind/minikube)    | ⬜      |
