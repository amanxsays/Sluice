# Sluice

[![CI](https://github.com/amanxsays/Sluice/actions/workflows/ci.yml/badge.svg)](https://github.com/amanxsays/Sluice/actions/workflows/ci.yml)

A distributed job scheduler and durable task queue, built in Java, purpose-built for rate-limited external API workloads (LLM APIs, third-party services with 429/Retry-After semantics, anything where "just retry immediately" makes things worse).

The name is a sluice gate: it controls the *rate* at which work flows through, not just whether it flows.

> **Status:** Weeks 1–5 are complete — schema through a real, benchmarked worker pipeline running via Docker Compose, with live Prometheus/Grafana observability and CI running the full test suite on every push. One item remains open by choice: wiring the reaper and recurring-schedule firing to an actual timer inside the running app — both are fully built and tested, just not yet invoked automatically. See [Project status](#project-status--roadmap) for exact detail.

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Features](#features)
- [Architecture](#architecture)
- [How it works](#how-it-works)
  - [Job lifecycle](#job-lifecycle)
  - [Crash recovery: leases, heartbeats, and the reaper](#crash-recovery-leases-heartbeats-and-the-reaper)
  - [Concurrent claiming with `SKIP LOCKED`](#concurrent-claiming-with-skip-locked)
  - [Recurring schedules](#recurring-schedules)
  - [Autonomous job processing](#autonomous-job-processing)
  - [Observability: metrics, Prometheus, and Grafana](#observability-metrics-prometheus-and-grafana)
  - [Schema](#schema)
- [Tech stack](#tech-stack)
- [Running it locally](#running-it-locally)
- [Running the tests](#running-the-tests)
- [Benchmark results](#benchmark-results)
- [Key design decisions](#key-design-decisions)
- [Project status / roadmap](#project-status--roadmap)

---

## Why this exists

Most "backend" side projects are wiring: call this API, save the result, call that API. Sluice is deliberately not that. It exists to answer a narrower, harder question: **what actually happens when a worker dies in the middle of doing something, and how do you build a system that survives that without losing work or doing it twice?**

That question comes directly from calling rate-limited LLM APIs in production — retries, backoff, and "did that request actually succeed before the process died" are not hypothetical problems there. Sluice is the queue that problem deserves, built from first principles on top of plain Postgres rather than bolted onto an existing message broker.

## Features

- **Postgres-backed queue.** No message broker — durability comes from the database itself.
- **Safe concurrent claiming.** Multiple worker processes compete for jobs via `SELECT ... FOR UPDATE SKIP LOCKED`, proven under real multi-threaded contention (not just unit tests).
- **At-least-once delivery.** Time-boxed leases + worker heartbeats + a reaper that reclaims jobs from workers that die silently.
- **Retries with exponential backoff + full jitter.** Failures don't retry instantly or in lockstep with every other failed job.
- **Dead letter queue.** Jobs that exceed a max-attempts threshold stop retrying automatically — via *either* an explicit failure report or a silent worker crash.
- **Idempotency keys.** Enforced atomically at the database level (`ON CONFLICT ... DO NOTHING`) — a duplicate submission returns the original job, never a second row, even under concurrent requests.
- **Job priorities.** Higher-priority jobs are claimed first; equal-priority jobs still respect arrival order.
- **Recurring schedules.** Cron-expression-driven job templates that spawn fresh executions on a real, computed schedule (via [cron-utils](https://github.com/jmrozanec/cron-utils)), independent of whether any individual spawned job succeeds or fails.
- **Autonomous worker pool.** A configurable number of background workers claim and process jobs continuously — started automatically when the app boots, no manual triggering.
- **Rate-limit-aware execution.** A real job handler makes actual HTTP calls to an upstream service; on a 429, it reads the response's own `Retry-After` header and uses that exact value as the backoff — not a guessed exponential delay.
- **Live observability.** Every `Worker` outcome and job-processing duration is recorded via Micrometer, exposed over HTTP by Spring Boot Actuator, scraped by Prometheus every 5 seconds, and visualized in a real Grafana dashboard — not a mockup of one.
- **Continuous integration.** The full test suite — including real Testcontainers-backed Postgres tests — runs automatically via GitHub Actions on every push.
- **One-command deployment.** The full stack (Postgres, the API + workers, a mock upstream service, Prometheus, and Grafana) runs via a single `docker compose up --build`, built from source via multi-stage Dockerfiles.
- **Measured, not assumed, performance.** A benchmark harness scales workers 1→8 against the mock upstream and records real throughput/p95 latency — see [results](#benchmark-results).
- **HTTP API** to enqueue jobs (`POST /jobs`).

## Architecture

Three Maven modules, split deliberately along a hard boundary:

```
sluice/
├── sluice-core/                 # queue engine — plain Java, zero Spring dependencies
│   ├── JobsRepository           # enqueue, claim, heartbeat, reclaim, markFailed, markCompleted, findById...
│   ├── JobSchedulesRepository   # findDueSchedules, fireSchedule
│   ├── BackoffCalculator        # pure logic: exponential backoff + jitter
│   ├── CronScheduleCalculator   # pure logic: cron expression → next occurrence
│   ├── Worker                   # the processing loop: claim → handle → complete/fail (Micrometer-instrumented)
│   ├── JobHandler                # interface: one job type's actual work
│   ├── SimulatedApiCallHandler   # a real handler — calls mock-upstream over HTTP
│   ├── RateLimitedException      # carries a real Retry-After value from upstream
│   └── db/migration/            # Flyway SQL migrations (travel to sluice-api via classpath)
│
├── sluice-api/                   # Spring Boot HTTP + wiring layer
│   ├── JobsController            # POST /jobs
│   ├── WorkerStartup             # starts the configured number of Workers on boot
│   ├── AppConfig                 # bridges Spring's DataSource → core's Spring-free classes
│   └── application.yml           # also exposes /actuator/prometheus
│
└── mock-upstream/                 # standalone Spring Boot app simulating a rate-limited API
    └── SimulateController          # POST /simulate — configurable latency + 429/Retry-After
```

Alongside the modules, at the repo root: `docker-compose.yml` (five services — Postgres, `sluice-api`, `mock-upstream`, Prometheus, Grafana), `prometheus.yml` (scrape config), and `.github/workflows/ci.yml` (GitHub Actions).

**Why the split:** `sluice-core` has no Spring dependency at all — not `spring-jdbc`, not `spring-tx`. It talks to Postgres through plain JDBC with manual transaction control. This means:

- Every concurrency and correctness test runs against real Postgres (via Testcontainers) with zero Spring context needed — fast, focused, nothing to configure.
- The dependency direction is enforced by Maven itself, not just convention — `sluice-core` physically cannot import anything from `sluice-api`.
- `sluice-core` could be extracted as a standalone library later with no rewriting.

`sluice-api` is the only place that knows both worlds exist — `AppConfig` is the seam: Spring builds a `DataSource` from `application.yml`, and hands it to `JobsRepository`'s plain constructor. `mock-upstream` is a third, fully independent module — it never depends on `sluice-core` at all — standing in for a real, rate-limited third-party API so benchmarks never have to hit anything real or paid.

```mermaid
flowchart TB
    client([HTTP client])

    subgraph api["sluice-api (Spring Boot)"]
        controller["JobsController<br/>POST /jobs"]
        config["AppConfig<br/>bridges Spring DataSource → core"]
        startup["WorkerStartup<br/>starts N Workers on boot"]
        actuator["Actuator<br/>/actuator/prometheus"]
    end

    subgraph core["sluice-core (plain Java, zero Spring)"]
        jobsRepo["JobsRepository<br/>enqueue · claim · heartbeat<br/>reclaimExpiredLeases · markFailed · markCompleted"]
        schedulesRepo["JobSchedulesRepository<br/>findDueSchedules · fireSchedule"]
        backoff["BackoffCalculator"]
        cron["CronScheduleCalculator"]
        worker["Worker<br/>processOnce · run<br/>(Micrometer counters + timer)"]
        handler["SimulatedApiCallHandler"]
    end

    subgraph mock["mock-upstream (Spring Boot)"]
        simulate["SimulateController<br/>POST /simulate"]
    end

    prom[(Prometheus)]
    graf[(Grafana)]
    db[(PostgreSQL)]

    client -->|JSON| controller
    controller --> jobsRepo
    config -.constructs.-> jobsRepo
    startup -.starts.-> worker
    worker --> jobsRepo
    worker --> handler
    worker -.records metrics.-> actuator
    handler -->|real HTTP call| simulate
    schedulesRepo -->|delegates spawn| jobsRepo
    jobsRepo --> db
    schedulesRepo --> db
    prom -->|scrapes every 5s| actuator
    graf -->|PromQL queries| prom
```

## How it works

### Job lifecycle

Every job moves through a small set of states. Retries loop back to `pending`; a job that exceeds its attempt budget — however it failed — lands in `dead_letter` and stops retrying automatically.

```mermaid
stateDiagram-v2
    [*] --> pending : enqueue()

    pending --> claimed : claim()\n(SKIP LOCKED, priority DESC, created_at ASC)

    claimed --> completed : worker succeeds
    claimed --> pending : markFailed()\n(attempts < max) — backoff delay applied
    claimed --> pending : reaper reclaims\n(lease expired, attempts < max)
    claimed --> dead_letter : markFailed()\n(attempts ≥ max)
    claimed --> dead_letter : reaper reclaims\n(lease expired, attempts ≥ max)

    completed --> [*]
    dead_letter --> [*]
```

### Crash recovery: leases, heartbeats, and the reaper

This is the core problem the project exists to solve. A claimed job gets a **lease** — a deadline, not a permanent hold. A live worker **renews** its lease periodically via heartbeats. If a worker dies, its lease simply expires, and a separate **reaper** process reclaims the job so someone else can pick it up.

```mermaid
sequenceDiagram
    participant A as Worker A
    participant DB as Postgres (jobs)
    participant R as Reaper
    participant B as Worker B

    A->>DB: claim() → status='claimed',<br/>lease_expires_at = now()+30s
    DB-->>A: Job #42

    Note over A: Worker A crashes<br/>mid-processing

    Note over DB: 30s pass, no heartbeat,<br/>lease_expires_at < now()

    loop runs on a timer
        R->>DB: reclaimExpiredLeases(maxAttempts)
    end
    DB-->>R: #42 reset → status='pending',<br/>attempts += 1, claimed_by = NULL

    B->>DB: claim()
    DB-->>B: Job #42
    Note over B: idempotency_key protects<br/>against duplicate side effects
```

### Concurrent claiming with `SKIP LOCKED`

Two workers polling at the same instant must never claim the same row. A naive `SELECT ... FOR UPDATE` would make the second worker *block* until the first commits — correct, but serializes every claim. `SKIP LOCKED` lets the second worker skip the locked row entirely and grab the next one instead, so both proceed genuinely in parallel.

```mermaid
sequenceDiagram
    participant A as Worker A
    participant B as Worker B
    participant DB as Postgres

    par Worker A
        A->>DB: SELECT ... FOR UPDATE SKIP LOCKED
        DB-->>A: locks row #7
    and Worker B (same instant)
        B->>DB: SELECT ... FOR UPDATE SKIP LOCKED
        Note over DB: row #7 already locked → skipped
        DB-->>B: locks row #8 instead
    end

    A->>DB: UPDATE #7 SET status='claimed'
    B->>DB: UPDATE #8 SET status='claimed'
```

Proven directly by a test that runs 4 threads against 20 seeded jobs and asserts every claimed id is unique — not just that claiming "works," but that it's safe under real thread contention.

### Recurring schedules

A `job_schedules` row is a *template*, decoupled from any single execution. A schedule firing spawns a normal job via the existing `enqueue` path — so whether that spawned job later succeeds, fails, or gets dead-lettered has **zero effect** on whether the *next* occurrence gets scheduled.

```mermaid
sequenceDiagram
    participant S as Scheduler (not yet wired to a timer)
    participant SR as JobSchedulesRepository
    participant JR as JobsRepository
    participant DB as Postgres

    S->>SR: findDueSchedules()
    SR->>DB: SELECT ... WHERE enabled AND next_run_at <= now()
    DB-->>SR: [schedule]

    S->>S: CronScheduleCalculator.nextRunAfter(cron, now)
    S->>SR: fireSchedule(schedule, jobsRepo, nextRunAt)
    SR->>JR: enqueue(jobType, payload, null, priority)
    JR->>DB: INSERT INTO jobs ... RETURNING *
    SR->>DB: UPDATE job_schedules SET next_run_at = ?
```

> The mechanism above is fully built and tested. What's **not** built yet: anything that actually calls it on a real timer — see [status](#project-status--roadmap).

### Autonomous job processing

`Worker` is the piece that turns the queue from "a set of methods proven correct in isolation" into a system that actually does work on its own. Each `Worker` runs on its own background thread, started automatically by `WorkerStartup` when `sluice-api` boots — no test harness, no manual trigger. `JobHandler` is the seam between the generic polling loop and job-type-specific work; `SimulatedApiCallHandler` is the one real handler currently wired in, making genuine HTTP calls to `mock-upstream` rather than simulating anything in-process.

```mermaid
sequenceDiagram
    participant W as Worker (background thread)
    participant JR as JobsRepository
    participant H as SimulatedApiCallHandler
    participant M as mock-upstream

    loop polls continuously
        W->>JR: claim(workerId, leaseSeconds)
    end
    JR-->>W: Job (or empty — sleep and retry)

    W->>H: handle(job)
    H->>M: POST /simulate (real HTTP call)

    alt 200 OK
        M-->>H: Success
        W->>JR: markCompleted(job.id(), workerId)
    else 429 + Retry-After
        M-->>H: 429, Retry-After: N
        H-->>W: throws RateLimitedException(N)
        W->>JR: markFailed(job.id(), workerId, N, maxAttempts)
        Note over W,JR: uses the server's own N seconds directly — not BackoffCalculator's guess
    end
```

### Observability: metrics, Prometheus, and Grafana

`Worker` records two things about every job it processes: a counter (`sluice.worker.jobs`, tagged by outcome — `completed`, `failed`, `no_handler`) and a timer (`sluice.worker.job.duration`, wrapped around the handler call in a `try`/`finally` so it captures failed attempts too, not just successful ones). Both are recorded via [Micrometer](https://micrometer.io/) — a framework-agnostic metrics facade, not a Spring library, so this instrumentation lives in `sluice-core` alongside everything else, the same reasoning as using plain Jackson for JSON.

`sluice-api` supplies the concrete, Spring Boot–autoconfigured `MeterRegistry` and exposes it over HTTP via Spring Boot Actuator at `/actuator/prometheus`. Prometheus scrapes that endpoint every 5 seconds and stores the resulting history; Grafana queries Prometheus and renders it as a live dashboard.

```mermaid
sequenceDiagram
    participant W as Worker
    participant MR as MeterRegistry (Micrometer)
    participant A as Actuator (/actuator/prometheus)
    participant P as Prometheus
    participant G as Grafana

    W->>MR: counter("sluice.worker.jobs", outcome).increment()
    W->>MR: timer("sluice.worker.job.duration").record(...)

    loop every 5s
        P->>A: GET /actuator/prometheus
        A-->>P: current counter + timer values
    end

    G->>P: PromQL query, e.g. sluice_worker_jobs_total
    P-->>G: stored time series
    G-->>G: renders as a live dashboard panel
```

Tests and the benchmark harness use `SimpleMeterRegistry` — Micrometer's plain in-memory implementation — so metrics recording is exercised everywhere `Worker` runs, without needing a real Prometheus instance for anything other than the live dashboard itself.

### Schema

```mermaid
erDiagram
    JOBS {
        bigint id PK
        text job_type
        jsonb payload
        text idempotency_key UK
        text status
        text claimed_by
        timestamptz claimed_at
        timestamptz lease_expires_at
        int attempts
        timestamptz available_at
        int priority
        timestamptz created_at
        timestamptz updated_at
    }

    JOB_SCHEDULES {
        bigint id PK
        text cron_expression
        text job_type
        jsonb payload
        int priority
        timestamptz next_run_at
        boolean enabled
        timestamptz created_at
        timestamptz updated_at
    }

    JOB_SCHEDULES ||--o{ JOBS : "spawns via fireSchedule (no stored FK)"
```

`status` is `TEXT` + a `CHECK` constraint (`pending`, `claimed`, `completed`, `failed`, `dead_letter`) rather than a Postgres `ENUM` — see [design decisions](#key-design-decisions).

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Web framework | Spring Boot 4.1.1 (`sluice-api`, `mock-upstream`) |
| Database access | Plain JDBC (`sluice-core`) — no ORM, explicit transaction control |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| HTTP client (for real outbound calls) | Java's built-in `java.net.http.HttpClient` |
| Metrics | [Micrometer](https://micrometer.io/), exposed via Spring Boot Actuator |
| Metrics storage & dashboards | Prometheus, Grafana |
| CI | GitHub Actions |
| Testing | JUnit 5, Testcontainers (real Postgres per test run), plain unit tests for pure-logic classes |
| Cron parsing | [cron-utils](https://github.com/jmrozanec/cron-utils) |
| Containerization | Docker, Docker Compose — multi-stage builds from source |

## Running it locally

**With Docker Compose (recommended)** — builds everything from source and starts the full stack, networked together, in one command:

```bash
docker compose up --build
```

Postgres comes up with a healthcheck gating startup order, so `sluice-api` never races it; `sluice-api` (port 8080) and `mock-upstream` (port 8081) both build from their own multi-stage Dockerfiles; Prometheus (port 9090) and Grafana (port 3000) start from their official images, no build step needed.

Enqueue a job:
```bash
# a job the real handler processes end-to-end — calls mock-upstream over HTTP
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{"jobType":"call-api","payload":"{\"latencyMs\":200,\"shouldFail\":false,\"retryAfterSeconds\":0}","idempotencyKey":null,"priority":0}'
```
Within about a second, one of the automatically-started background workers claims it, calls `mock-upstream` for real, and marks it completed — no further action needed.

**Watch it happen live:**
- Prometheus: `http://localhost:9090/targets` — confirm `sluice-api` shows `UP`.
- Grafana: `http://localhost:3000` (default login `admin` / `admin`) — add Prometheus as a data source at `http://prometheus:9090`, then query `sluice_worker_jobs_total` or `sluice_worker_job_duration_seconds_sum` directly to see real metrics from the jobs you just enqueued.

**Manual / without Docker (for local dev against `sluice-core` changes):**
```bash
docker run --name sluice-postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16-alpine
mvn install
mvn -pl sluice-api spring-boot:run
```
`sluice-api/src/main/resources/application.yml` points at `localhost:5432` by default for this path.

## Running the tests

```bash
mvn -pl sluice-core test
```

Requires a running Docker daemon — most tests spin up a disposable Postgres container via Testcontainers per run. The pure-logic test classes (`BackoffCalculatorTest`, `CronScheduleCalculatorTest`) don't touch Docker at all and run in milliseconds.

The same suite runs automatically on every push via [GitHub Actions](.github/workflows/ci.yml) — no Docker Desktop to remember to start; GitHub's runners have Docker available by default. One test, `WorkerTest.processOnceUsesRetryAfterOnRateLimit`, depends on a real `mock-upstream` instance being reachable over HTTP: locally, where it usually is, the test genuinely verifies the `Retry-After` behavior end-to-end; on a bare CI runner where nothing but Postgres is provisioned, it skips cleanly via `Assumptions.assumeTrue(...)` rather than failing — a missing optional dependency and a real regression are different situations and are reported differently on purpose.

## Benchmark results

100 jobs enqueued per phase, each carrying a simulated 50ms upstream latency, processed against a real running `mock-upstream` instance (not an in-process stub), with worker count scaled 1 → 2 → 4 → 8. `sluice-api`'s own background workers were stopped for the duration of the run, so each phase's worker count is exactly what it claims to be.

| Workers | Throughput (jobs/sec) | p95 latency (ms) |
|---|---|---|
| 1 | 9.23 | 10,203 |
| 2 | 18.14 | 4,834 |
| 4 | 32.49 | 2,610 |
| 8 | 44.84 | 1,915 |

![Benchmark results: throughput increasing and p95 latency decreasing as worker count scales from 1 to 8](sluice-benchmark-results.png)

Throughput scales substantially with worker count — roughly **4.9×** going from 1 to 8 workers — and p95 latency drops correspondingly, from over 10 seconds down to under 2. Scaling isn't perfectly linear past 4 workers, which is expected and worth stating plainly rather than hiding: every worker still contends for the same single Postgres instance and the same single `mock-upstream` instance, and the benchmark harness itself opens an unpooled JDBC connection per call rather than using a connection pool — both are real, identifiable ceilings on how far throughput can climb before something else becomes the bottleneck.

Produced by `sluice-core/src/test/java/dev/sluice/core/BenchmarkRunner.java`, run manually against a `docker compose up -d` Postgres and `mock-upstream`.

## Key design decisions

A few choices worth calling out, since the reasoning is most of the point of this project:

- **`SELECT ... FOR UPDATE SKIP LOCKED` over a plain `FOR UPDATE`.** A blocking lock serializes every claim across workers; `SKIP LOCKED` lets workers proceed genuinely in parallel by skipping rows another transaction already holds.
- **Leases + heartbeats, not "trust the worker to call back."** A dead process can't run cleanup code. A lease turns "is this worker still alive?" into a simple, checkable deadline instead of an unanswerable question.
- **At-least-once delivery + idempotency keys, not a doomed attempt at exactly-once.** Exactly-once delivery across independently-failing processes is a known-unsolvable problem in distributed systems. The realistic target — used by every serious queue system — is guaranteeing a job *will* run, and making it safe if it runs more than once.
- **`BIGINT GENERATED ALWAYS AS IDENTITY`, not `UUID`, for primary keys.** Sequential ids insert at the right edge of the B-tree index; random UUIDs scatter across it, causing page splits that degrade insert throughput under load — a real cost given this project's own benchmark goals.
- **`status TEXT` + `CHECK`, not a Postgres `ENUM`.** Adding a new status value (`dead_letter`) is a plain `ALTER TABLE ... DROP/ADD CONSTRAINT` — no enum-type migration ceremony, and no extra JDBC type-mapping code to maintain.
- **`ON CONFLICT (idempotency_key) DO NOTHING`, not check-then-insert.** Checking existence and inserting as two separate steps is a race condition under concurrency — the exact class of bug `SKIP LOCKED` exists to prevent elsewhere. The conflict has to be resolved atomically, inside one statement.
- **A `job_schedules` table decoupled from individual job executions**, not one job re-enqueuing the next occurrence of itself. Self-chaining means a single failed link silently ends the schedule forever. A separate table with its own `next_run_at` survives any individual execution's failure.
- **A `Worker.processOnce()` / `run()` split, not one big infinite-loop method.** `processOnce()` does exactly one claim-and-handle cycle and returns a boolean — directly unit-testable, no test can cleanly assert against an infinite loop. `run()` is a thin, effectively untested wrapper that just calls it repeatedly.
- **`RateLimitedException` as its own type, not a generic catch-all.** A rate-limited failure carries real information — how long the upstream itself wants you to wait — that shouldn't be discarded in favor of a locally-computed guess. A specific exception type, caught before the general handler, is what lets that real signal actually reach `markFailed`.
- **Multi-stage Dockerfiles, building from source.** A `maven` build stage compiles the full reactor; only the finished jar is copied into a slim `eclipse-temurin` JRE runtime image — the shipped image never carries Maven, the JDK, or source code.
- **Micrometer lives in `sluice-core`, not `sluice-api`.** Same reasoning as Jackson: it's a framework-agnostic facade, not a Spring library, so `Worker` can record metrics without violating core's zero-Spring rule. `sluice-api` only supplies the concrete, auto-configured registry Spring Boot builds.
- **A skipped test, not a failing one, when an optional dependency is unavailable.** `WorkerTest`'s rate-limit test needs a real `mock-upstream` reachable over HTTP — genuinely absent on a bare CI runner, not a bug. `Assumptions.assumeTrue(...)` turns that into an honest "couldn't verify here" rather than a false red X.

## Project status / roadmap

- [x] **Week 1** — Schema + Flyway, enqueue endpoint, `SKIP LOCKED` claiming, concurrency-tested.
- [x] **Week 2** — Leases, heartbeats, reaper, backoff + jitter, dead letter queue.
- [x] **Week 3 (core logic)** — Idempotency keys, job priorities, `job_schedules` + real cron-based next-run computation, all tested.
- [ ] **Week 3 (remaining)** — Wire the reaper (`reclaimExpiredLeases`) and `findDueSchedules`/`fireSchedule` to actual recurring timers inside `sluice-api`. Both are fully built and tested, but only ever invoked manually or in tests today.
- [x] **Week 4** — `Worker`/`JobHandler` processing loop, a real rate-limit-aware handler (`SimulatedApiCallHandler` + `RateLimitedException`), a standalone `mock-upstream` service, workers wired to start automatically in `sluice-api`, the full stack running via Docker Compose (multi-stage builds), and a benchmark measuring real throughput/p95 latency across 1→8 workers — see [results](#benchmark-results).
- [x] **Week 5** — `Worker` instrumented with Micrometer counters and a timer, exposed via Spring Boot Actuator, scraped by Prometheus, and visualized live in a real Grafana dashboard. GitHub Actions CI running the full test suite — including Testcontainers-backed Postgres tests — on every push.
- [ ] **Deferred by choice** — Port the concurrency, retry, and idempotency patterns learned building Sluice back into the author's other LLM/RAG projects.