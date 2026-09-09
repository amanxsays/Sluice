# Sluice — Demo Script (personal notes)

Quick reference for walking someone through the project live. ~10-15 min full version, ~5 min short version at the bottom.

## Pre-flight check

- [ ] Docker Desktop running (`docker ps` returns cleanly)
- [ ] No leftover manual containers holding ports 8080/8081/5432/9090/3000 (`docker ps` — stop anything not from this compose project)
- [ ] Terminal open in the repo root

---

## 1. Start everything — one command

```bash
docker compose up --build
```
**Say:** "Whole stack — Postgres, the API and its background workers, a mock upstream service, Prometheus, and Grafana — comes up from one command, built from source."

---

## 2. Prove the happy path

```bash
curl -X POST http://localhost:8080/jobs -H "Content-Type: application/json" \
  -d '{"jobType":"call-api","payload":"{\"latencyMs\":200,\"shouldFail\":false,\"retryAfterSeconds\":0}","idempotencyKey":null,"priority":0}'
```

Check it landed and completed on its own:
```bash
docker exec -it sluice-postgres-1 psql -U postgres -c "SELECT id, status, claimed_by FROM jobs ORDER BY id DESC LIMIT 1;"
```
**Say:** "Nothing else was triggered — a background worker picked this up on its own, made a real HTTP call to the mock upstream, and completed it."

---

## 3. The centerpiece — crash recovery / concurrency

**Fast, reliable option — run the concurrency test live:**
```bash
mvn -pl sluice-core test -Dtest=JobsRepositoryTest#claimNeverAssignsSameJobToWorker
```
**Say while it runs:** "This spins up 4 real threads racing over 20 jobs against a real Postgres instance, and asserts every claimed job id is unique — proving `SKIP LOCKED` actually prevents double-claiming under genuine thread contention, not just in theory."

**More visual option, if there's time — actually kill a worker mid-job:**
```bash
# enqueue a job, then immediately:
docker kill sluice-sluice-api-1
# wait ~30s (the lease duration), then bring it back:
docker compose up -d
# check the job — should be reclaimed, not stuck
docker exec -it sluice-postgres-1 psql -U postgres -c "SELECT id, status, claimed_by, attempts FROM jobs ORDER BY id DESC LIMIT 1;"
```

---

## 4. Rate-limit awareness

```bash
curl -X POST http://localhost:8080/jobs -H "Content-Type: application/json" \
  -d '{"jobType":"call-api","payload":"{\"latencyMs\":0,\"shouldFail\":true,\"retryAfterSeconds\":10}","idempotencyKey":null,"priority":0}'
```
Check `available_at` on that job — should land ~10s out, matching the value passed:
```bash
docker exec -it sluice-postgres-1 psql -U postgres -c "SELECT id, status, available_at FROM jobs ORDER BY id DESC LIMIT 1;"
```
**Say:** "The mock upstream returned a real 429 with `Retry-After: 10`, and the worker used that exact value — not its own guess."

---

## 5. Show the numbers + live dashboard

- Open README's benchmark section / `sluice-benchmark-results.png`.
  **Say:** "Throughput scaled roughly 4.9× from 1 to 8 workers, measured, not assumed."
- Open Grafana: `http://localhost:3000` (admin/admin), the "Sluice Overview" dashboard.
- Enqueue 2-3 more jobs live (reuse the command from step 2) and watch the counter panel move in real time. **Most visually compelling 5 seconds of the demo.**

---

## 6. Prove it's tested and CI'd

```bash
mvn -pl sluice-core test
```
Or just point at the green checkmark on the GitHub Actions tab.
**Say:** "25 tests, every one against a real Postgres via Testcontainers, running automatically on every push."

---

## 7. Close with the README

Pull up the Mermaid diagrams + "Key design decisions" section. This is the answer bank for "why did you..." questions:

- Why `SKIP LOCKED` over a blocking `FOR UPDATE`?
- Why leases + heartbeats instead of trusting a worker to call back?
- Why at-least-once + idempotency instead of chasing exactly-once?
- Why `BIGINT IDENTITY` not `UUID` for primary keys?
- Why `TEXT + CHECK` not a Postgres `ENUM` for status?
- Why `ON CONFLICT DO NOTHING` instead of check-then-insert?
- Why a separate `job_schedules` table instead of self-chaining jobs?
- Why `RateLimitedException` as its own type?

---

## If only 5 minutes: steps 1, 2, 3 (test option), 5. Enqueue → concurrency test → chart/dashboard.
