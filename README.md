# Distributed Job Queue

A crash-safe priority job queue: **Redis** dispatches job IDs, **PostgreSQL** owns job state,
and a pool of **Spring Boot** workers processes jobs with retries, a dead-letter queue and automatic
recovery from worker crashes. Includes a live **React dashboard** (with buttons to inject failures
and crash workers) and **Prometheus + Grafana** observability.

```
docker compose up -d --build      # then open http://localhost:3000
```

| | |
|---|---|
| Dashboard (live, SSE) | http://localhost:3000 |
| Grafana (anonymous view) | http://localhost:3000/grafana |
| Prometheus | http://localhost:9090 |

## Measured results

All numbers come from the scripts in [`scripts/`](scripts) and [`bench/`](bench), run against this
Compose stack on a laptop (Docker Desktop, Windows 11). Raw output is in [`bench/results/`](bench/results).

| What | Result | How it was measured |
|---|---|---|
| Job-fetch (retry claim) latency | **p95 431.9 ms → 5.1 ms (−98.8%)**, 33 → 2,504 claims/s (**77×**) | `bench/db/run.sh`: pgbench, 1M-row table, 8 concurrent pollers, no index + `FOR UPDATE` vs composite index + `FOR UPDATE SKIP LOCKED` |
| `SKIP LOCKED` alone (index in place) | p95 32.6 ms → 5.1 ms (−84%), 4× throughput | same benchmark |
| Permanent failures with retries | DLQ rate **30.1% → 1.07% (−96%)** at a 30% injected transient-failure rate | `loadtest.mjs`, 3,000 jobs, `maxRetries` 0 vs 3 |
| Worker crashes | **0 of 4,000 jobs lost** across 4 `SIGKILL`s of busy containers; 16 in-flight jobs reclaimed | `chaos-test.mjs` |
| Horizontal scaling | 63.8 → 129.7 → 260.9 jobs/s at 4 → 8 → 16 worker threads (**linear, 4.09× at 4×**) | `loadtest.mjs`, 50 ms jobs, `--scale worker=1/2/4` |
| Latency below saturation | p50 108 ms / **p95 162 ms** end-to-end for 50 ms jobs at a steady 100 jobs/s | `loadtest.mjs --rate 100` |
| Peak throughput (no-op jobs) | ~500 jobs/s durable; 1,216 jobs/s with `synchronous_commit=off` | bottleneck is the WAL fsync per commit; durability kept on by design |
| Tests | 52 tests (JUnit 5, Mockito, Testcontainers); **91.5% / 96.5% line coverage** (worker / API), 85% gate in CI | `./mvnw verify` |

## Architecture

```
            ┌──────────── React dashboard (nginx) ─────────────┐
            │  /api  ──►  api-service ──► Postgres (jobs table)│
            │  SSE   ◄──  live stats ◄── Redis pub/sub events  │
            └──────────────────────┬───────────────────────────┘
                                   │ LPUSH id
                     Redis: queue:high | queue:medium | queue:low
                                   │ Lua: RPOPLPUSH → processing:{worker}
          ┌────────────────────────┴────────────────────────┐
     worker container 1 (4 threads)            worker container 2 (4 threads)
          │ claim: UPDATE … WHERE status='QUEUED'  (one winner)
          │ heartbeat thread: heartbeat_at every 2 s
          └─ retry scheduler + recovery reaper: FOR UPDATE SKIP LOCKED
```

**Job lifecycle:** `QUEUED → PROCESSING → SUCCESS`, or `→ RETRYING → QUEUED …` and finally `→ DLQ`.

### Design decisions

- **Postgres is the source of truth; Redis only carries IDs.** Every transition is a single
  conditional `UPDATE`, so a duplicate ID in Redis is harmless: the second claim matches 0 rows.
- **Reliable pop.** A Lua script atomically moves an ID from the highest-priority non-empty queue
  into the thread's own `processing:{workerId}` list. An ID is never in flight only in memory.
- **Weighted priority, not strict priority.** Pulls cycle 6:3:1 (HIGH:MEDIUM:LOW), falling back in
  priority order, so LOW jobs can't starve under sustained HIGH load.
- **Fencing.** Completion updates require `worker_id = me AND status = 'PROCESSING'`. A worker that
  was presumed dead and reclaimed can't overwrite the new owner's result.
- **Three recovery sweeps** (safe to run from every container at once):
  1. stale heartbeat → `PROCESSING` rows reclaimed;
  2. dead worker's processing list (its TTL key expired) → atomically `RENAME`d and drained;
  3. orphans (committed but never pushed to Redis) → re-pushed only when Redis is empty.
- **Exponential backoff with full jitter**: `delay = random(0, min(cap, base·2ⁿ))`, which avoids
  synchronised retry storms against a recovering dependency.
- **Delivery semantics: at-least-once.** A job killed mid-execution runs again; real handlers should
  be idempotent. The chaos test reports re-executions explicitly.
- **Composite indexes per access path**: `(status, next_retry_at)`, `(status, heartbeat_at)`,
  `(status, priority, created_at)`, `(type, status)`. Schema is managed by Flyway.

## API

| Method | Path | |
|---|---|---|
| POST | `/api/jobs` | `{"type":"EMAIL","priority":"HIGH","payload":{…},"maxRetries":3}` |
| POST | `/api/jobs/batch` | `{"count":500,"durationMs":50,"failRate":0.3}` (synthetic load) |
| GET | `/api/jobs?status=DLQ&page=0&size=25` · `/api/jobs/{id}` | |
| POST | `/api/dlq/{id}/redrive` · `/api/dlq/redrive-all` | |
| GET | `/api/stream` (SSE) · `/api/stats` · `/api/stats/runs/{type}` | |
| PUT | `/api/settings` `{"failureRate":0.2}` | injected failure rate for the demo |
| POST | `/api/chaos/crash-worker` | halts one worker JVM (demo) |

## Reproducing the benchmarks

```bash
docker compose up -d --build
# DB claim latency (pgbench inside the postgres container)
docker compose cp bench/db/. postgres:/bench/ && docker compose exec -T postgres sh /bench/run.sh
# throughput / latency / retries
node scripts/loadtest.mjs --count 4000 --duration 50
node scripts/loadtest.mjs --count 3000 --duration 50 --rate 100
node scripts/loadtest.mjs --count 3000 --duration 20 --failRate 0.3 --maxRetries 3
# crash safety
node scripts/chaos-test.mjs --count 4000 --duration 150 --kills 4 --every 20
# scaling
docker compose up -d --scale worker=4
```

## Development

```bash
cd api-service && ./mvnw verify      # needs Docker running (Testcontainers)
cd worker-service && ./mvnw verify
cd dashboard && npm install && npm run dev   # proxies /api to localhost:3000
```

CI (GitHub Actions) runs both test suites with the coverage gate, builds the dashboard and builds
all images. See [DEPLOY.md](DEPLOY.md) for hosting the live demo on one small VM.

**Stack:** Java 17, Spring Boot 3.5, Spring Data JPA / JDBC, PostgreSQL 15, Redis 7, Flyway,
Micrometer, Prometheus, Grafana, React + TypeScript (Vite), nginx, Docker Compose, GitHub Actions,
JUnit 5, Mockito, Testcontainers, JaCoCo.
