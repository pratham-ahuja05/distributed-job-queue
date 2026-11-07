#!/usr/bin/env node
// Chaos test: SIGKILL worker containers while they are processing, then verify that every
// submitted job still reaches a terminal state (no job lost) and count re-executions.
//
//   node scripts/chaos-test.mjs --count 4000 --duration 150 --kills 4 --every 20
//
// Requires the Compose stack (docker compose up -d) and the docker CLI on PATH.

import { execSync } from "node:child_process";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => {
    if (a.startsWith("--")) acc.push([a.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);
const BASE = args.base ?? process.env.BASE_URL ?? "http://localhost:3000";
const count = Number(args.count ?? 2000);
const durationMs = Number(args.duration ?? 150);
const kills = Number(args.kills ?? 5);
const everyS = Number(args.every ?? 7);
const label = args.label ?? `chaos-${Date.now().toString(36)}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (cmd) => execSync(cmd, { encoding: "utf8" }).trim();

async function call(path, init) {
  const res = await fetch(BASE + path, { headers: { "Content-Type": "application/json" }, ...init });
  const body = await res.json();
  if (!res.ok) throw new Error(`${res.status} ${JSON.stringify(body)}`);
  return body;
}

console.error(`run=${label}: ${count} jobs x ${durationMs} ms, ${kills} SIGKILLs every ${everyS}s`);
const t0 = Date.now();
for (let sent = 0; sent < count; sent += 1000) {
  await call("/api/jobs/batch", {
    method: "POST",
    body: JSON.stringify({ count: Math.min(1000, count - sent), type: label, durationMs, failRate: 0, maxRetries: 3 }),
  });
}

const killLog = [];
for (let k = 0; k < kills; k++) {
  await sleep(everyS * 1000);
  const stats = await call(`/api/stats/runs/${label}`);
  if (stats.pending === 0) break;
  // Kill the container doing the most work right now, so the crash really interrupts jobs.
  // Worker instance ids are "<container hostname>-<boot suffix>"; hostname = short container id.
  const busyBy = {};
  for (const w of (await call("/api/stats")).workers) {
    if (w.state === "BUSY") busyBy[w.instance] = (busyBy[w.instance] ?? 0) + 1;
  }
  const [instance, inFlight] = Object.entries(busyBy).sort((x, y) => y[1] - x[1])[0] ?? [];
  if (!instance) { console.error("  no busy worker to kill, skipping"); continue; }
  const victim = instance.slice(0, instance.lastIndexOf("-"));
  const name = sh(`docker inspect -f "{{.Name}}" ${victim}`).replace(/^\//, "");
  sh(`docker kill --signal KILL ${victim}`);
  killLog.push({ atS: Math.round((Date.now() - t0) / 1000), container: name, jobsInFlightKilled: inFlight, pendingAtKill: stats.pending });
  console.error(`  t+${killLog.at(-1).atS}s SIGKILL ${name} with ${inFlight} jobs in flight (${stats.pending} pending)`);
  // Bring it back the way an orchestrator would (restart policies don't apply to manual kills).
  setTimeout(() => { try { sh(`docker start ${victim}`); } catch { /* already running */ } }, 3000);
}

let stats;
for (;;) {
  stats = await call(`/api/stats/runs/${label}`);
  if (stats.pending === 0) break;
  if (Date.now() - t0 > 600_000) break;
  await sleep(1000);
}

const result = {
  run: label,
  submitted: count,
  rowsFound: stats.total,
  succeeded: stats.success,
  deadLettered: stats.dlq,
  stillPending: stats.pending,
  lost: count - stats.success - stats.dlq,
  killsDelivered: killLog.length,
  jobsInFlightKilled: killLog.reduce((n, k) => n + k.jobsInFlightKilled, 0),
  jobsRecovered: stats.recovered,
  reExecutions: stats.reExecutions,
  wallSeconds: Number(((Date.now() - t0) / 1000).toFixed(1)),
  kills: killLog,
};
console.log(JSON.stringify(result, null, 2));
process.exit(result.lost === 0 ? 0 : 1);
