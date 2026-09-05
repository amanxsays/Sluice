# Sluice

A distributed job scheduler and durable task queue, built in Java, purpose-built for rate-limited external API workloads (LLM APIs, third-party services with 429/Retry-After semantics, anything where "just retry immediately" makes things worse).

The name is a sluice gate: it controls the *rate* at which work flows through, not just whether it flows.

> **Status:** actively under development. Weeks 1–2 (below) are complete and fully tested. Week 3 is substantially complete — see [Project status](#project-status--roadmap) for exactly what's done vs. still open.

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
  - [Schema](#schema)
- [Tech stack](#tech-stack)
- [Running it locally](#running-it-locally)
- [Running the tests](#running-the-tests)
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
- **HTTP API** to enqueue jobs (`POST /jobs`).

## Architecture

Two Maven modules, split deliberately along a hard boundary:

```
sluice/
├── sluice-core/            # queue engine — plain Java, zero Spring dependencies
│   ├── JobsRepository      # enqueue, claim, heartbeat, reclaim, markFailed, findById...
│   ├── JobSchedulesRepository
│   ├── BackoffCalculator   # pure logic: exponential backoff + jitter
│   ├── CronScheduleCalculator  # pure logic: cron expression → next occurrence
│   └── db/migration/       # Flyway SQL migrations (travel to sluice-api via classpath)
│
└── sluice-api/              # Spring Boot HTTP + wiring layer
    ├── JobsController       # POST /jobs
    ├── AppConfig            # bridges Spring's DataSource → core's Spring-free classes
    └── application.yml
```

**Why the split:** `sluice-core` has no Spring dependency at all — not `spring-jdbc`, not `spring-tx`. It talks to Postgres through plain JDBC with manual transaction control. This means:

- Every concurrency and correctness test runs against real Postgres (via Testcontainers) with zero Spring context needed — fast, focused, nothing to configure.
- The dependency direction is enforced by Maven itself, not just convention — `sluice-core` physically cannot import anything from `sluice-api`.
- `sluice-core` could be extracted as a standalone library later with no rewriting.

`sluice-api` is the only place that knows both worlds exist — `AppConfig` is the seam: Spring builds a `DataSource` from `application.yml`, and hands it to `JobsRepository`'s plain constructor.

```mermaid
flowchart TB
    client([HTTP client])

    subgraph api["sluice-api (Spring Boot)"]
        controller["JobsController<br/>POST /jobs"]
        config["AppConfig<br/>bridges Spring DataSource → core"]
    end

    subgraph core["sluice-core (plain Java, zero Spring)"]
        jobsRepo["JobsRepository<br/>enqueue · claim · heartbeat<br/>reclaimExpiredLeases · markFailed"]
        schedulesRepo["JobSchedulesRepository<br/>findDueSchedules · fireSchedule"]
        backoff["BackoffCalculator"]
        cron["CronScheduleCalculator"]
    end

    db[(PostgreSQL)]

    client -->|JSON| controller
    controller --> jobsRepo
    config -.constructs.-> jobsRepo
    schedulesRepo -->|delegates spawn| jobsRepo
    jobsRepo --> db
    schedulesRepo --> db
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
| Web framework | Spring Boot 4.1.1 (`sluice-api` only) |
| Database access | Plain JDBC (`sluice-core`) — no ORM, explicit transaction control |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Testing | JUnit 5, Testcontainers (real Postgres per test run), plain unit tests for pure-logic classes |
| Cron parsing | [cron-utils](https://github.com/jmrozanec/cron-utils) |

## Running it locally

```bash
# 1. Start a real Postgres for the app to talk to
docker run --name sluice-postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16-alpine

# 2. Build the whole reactor (installs sluice-core to your local repo)
mvn install

# 3. Run the API — Flyway migrates automatically on startup
mvn -pl sluice-api spring-boot:run
```

`sluice-api/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: postgres
```

Enqueue a job:
```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{"jobType":"send-email","payload":"{\"to\":\"a@b.com\"}","idempotencyKey":null}'
```

## Running the tests

```bash
mvn -pl sluice-core test
```

Requires a running Docker daemon — most tests spin up a disposable Postgres container via Testcontainers per run. The pure-logic test classes (`BackoffCalculatorTest`, `CronScheduleCalculatorTest`) don't touch Docker at all and run in milliseconds.

## Key design decisions

A few choices worth calling out, since the reasoning is most of the point of this project:

- **`SELECT ... FOR UPDATE SKIP LOCKED` over a plain `FOR UPDATE`.** A blocking lock serializes every claim across workers; `SKIP LOCKED` lets workers proceed genuinely in parallel by skipping rows another transaction already holds.
- **Leases + heartbeats, not "trust the worker to call back."** A dead process can't run cleanup code. A lease turns "is this worker still alive?" into a simple, checkable deadline instead of an unanswerable question.
- **At-least-once delivery + idempotency keys, not a doomed attempt at exactly-once.** Exactly-once delivery across independently-failing processes is a known-unsolvable problem in distributed systems. The realistic target — used by every serious queue system — is guaranteeing a job *will* run, and making it safe if it runs more than once.
- **`BIGINT GENERATED ALWAYS AS IDENTITY`, not `UUID`, for primary keys.** Sequential ids insert at the right edge of the B-tree index; random UUIDs scatter across it, causing page splits that degrade insert throughput under load — a real cost given this project's own benchmark goals.
- **`status TEXT` + `CHECK`, not a Postgres `ENUM`.** Adding a new status value (`dead_letter`) is a plain `ALTER TABLE ... DROP/ADD CONSTRAINT` — no enum-type migration ceremony, and no extra JDBC type-mapping code to maintain.
- **`ON CONFLICT (idempotency_key) DO NOTHING`, not check-then-insert.** Checking existence and inserting as two separate steps is a race condition under concurrency — the exact class of bug `SKIP LOCKED` exists to prevent elsewhere. The conflict has to be resolved atomically, inside one statement.
- **A `job_schedules` table decoupled from individual job executions**, not one job re-enqueuing the next occurrence of itself. Self-chaining means a single failed link silently ends the schedule forever. A separate table with its own `next_run_at` survives any individual execution's failure.

## Project status / roadmap

- [x] **Week 1** — Schema + Flyway, enqueue endpoint, `SKIP LOCKED` claiming, concurrency-tested.
- [x] **Week 2** — Leases, heartbeats, reaper, backoff + jitter, dead letter queue.
- [x] **Week 3 (core logic)** — Idempotency keys, job priorities, `job_schedules` + real cron-based next-run computation, all tested.
- [ ] **Week 3 (remaining)** — Wire `findDueSchedules` / `fireSchedule` to an actual `@Scheduled` timer loop in `sluice-api`.
- [ ] **Week 4** — Docker Compose for the full stack, workers scaled 1→8 against a mock rate-limited upstream, throughput/p95 latency benchmark with a chart.
- [ ] **Week 5** — Metrics dashboard (Micrometer + Prometheus + Grafana), GitHub Actions CI, architecture diagram polish.