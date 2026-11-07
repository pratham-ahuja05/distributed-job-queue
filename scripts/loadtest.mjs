#!/usr/bin/env node
// End-to-end load test against a running stack. No dependencies (Node 18+).
//
//   node scripts/loadtest.mjs --count 5000 --duration 0
//   node scripts/loadtest.mjs --count 2000 --duration 50 --failRate 0.3 --maxRetries 3
//   node scripts/loadtest.mjs --count 3000 --duration 50 --rate 100   (steady 100 jobs/s)
//
// Every job of a run shares a unique `type`, and all numbers are computed by Postgres
// from the job rows (GET /api/stats/runs/{type}) - nothing is estimated client-side.

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => {
    if (a.startsWith("--")) acc.push([a.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);

const BASE = args.base ?? process.env.BASE_URL ?? "http://localhost:3000";
const count = Number(args.count ?? 2000);
const durationMs = Number(args.duration ?? 50);
const failRate = Number(args.failRate ?? 0);
const maxRetries = Number(args.maxRetries ?? 3);
const batchSize = Number(args.batch ?? 1000);
const rate = args.rate ? Number(args.rate) : null; // jobs/s; null = submit everything at once
const timeoutS = Number(args.timeout ?? 600);
const label = args.label ?? `load-${Date.now().toString(36)}`;
const json = args.json !== undefined;

async function call(path, init) {
  const res = await fetch(BASE + path, { headers: { "Content-Type": "application/json" }, ...init });
  const body = await res.json();
  if (!res.ok) throw new Error(`${res.status} ${JSON.stringify(body)}`);
  return body;
}

const log = (...m) => { if (!json) console.error(...m); };

log(`run=${label} jobs=${count} durationMs=${durationMs} failRate=${failRate} maxRetries=${maxRetries} -> ${BASE}`);

const submit = (n) => call("/api/jobs/batch", {
  method: "POST",
  body: JSON.stringify({ count: n, type: label, durationMs, failRate, maxRetries }),
});

const t0 = Date.now();
if (rate) {
  // Steady arrival: rate/10 jobs every 100 ms, scheduled against the wall clock (no drift).
  const perTick = Math.max(1, Math.round(rate / 10));
  for (let sent = 0, tick = 0; sent < count; tick++) {
    const n = Math.min(perTick, count - sent);
    await submit(n);
    sent += n;
    const wait = t0 + (tick + 1) * 100 - Date.now();
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  }
} else {
  for (let sent = 0; sent < count; sent += batchSize) {
    await submit(Math.min(batchSize, count - sent));
  }
}
log(`submitted ${count} jobs in ${Date.now() - t0} ms`);

let stats;
for (;;) {
  stats = await call(`/api/stats/runs/${encodeURIComponent(label)}`);
  if (stats.pending === 0 && stats.total === count) break;
  if ((Date.now() - t0) / 1000 > timeoutS) throw new Error(`timed out with ${stats.pending} pending`);
  log(`  ${stats.success} ok, ${stats.dlq} dlq, ${stats.pending} pending`);
  await new Promise((r) => setTimeout(r, 1000));
}

const r = (x, d = 1) => (x == null ? null : Number(x.toFixed(d)));
const summary = {
  run: label,
  jobs: stats.total,
  succeeded: stats.success,
  deadLettered: stats.dlq,
  dlqRatePct: r((100 * stats.dlq) / stats.total, 2),
  throughputPerSec: r(stats.throughputPerSec),
  wallSeconds: r(stats.spanSeconds, 2),
  p50LatencyMs: r(stats.p50LatencyMs),
  p95LatencyMs: r(stats.p95LatencyMs),
  p99LatencyMs: r(stats.p99LatencyMs),
  p50QueueWaitMs: r(stats.p50WaitMs),
  p95QueueWaitMs: r(stats.p95WaitMs),
  avgAttempts: r(stats.avgAttempts, 3),
  reExecutions: stats.reExecutions,
  recovered: stats.recovered,
  config: { durationMs, failRate, maxRetries, rate },
};
console.log(JSON.stringify(summary, null, json ? 0 : 2));
