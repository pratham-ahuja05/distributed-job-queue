/**
 * In-browser simulation of the job queue, used for the static (Vercel) demo.
 *
 * It follows the same rules as the real services so the demo behaves honestly:
 *  - three priority lines, pulled with the same 6:3:1 weighted schedule + strict fallback
 *  - 2 "computers" x 4 worker threads
 *  - failures retry with exponential backoff + full jitter (base 1 s), give up after 3 retries
 *  - a crashed computer's in-flight tasks stay "Working" until their heartbeat is 15 s stale,
 *    then get rescued back into line; the computer restarts under a new identity
 * Produces the same Snapshot / event shapes as the real API.
 */
import type { Job, JobEvent, JobStatus, Priority, SeriesPoint, Snapshot, WorkerInfo } from "../types";

const SLOTS = [1, 2, 1, 3, 1, 2, 1, 1, 2, 1];
const PRIORITY_NAME: Record<number, Priority> = { 1: "HIGH", 2: "MEDIUM", 3: "LOW" };
const LEVEL: Record<Priority, number> = { HIGH: 1, MEDIUM: 2, LOW: 3 };
const THREADS = 4;
const COMPUTERS = 2;
const STALE_MS = 15_000;
const RESTART_MS = 8_000;
const RETRY_BASE_MS = 1_000;
const RETRY_CAP_MS = 60_000;
const KEEP_FINISHED_MS = 3 * 60_000;

interface SimJob {
  id: number;
  type: string;
  priority: number;
  status: JobStatus;
  retryCount: number;
  maxRetries: number;
  attempts: number;
  recoveredCount: number;
  durationMs: number;
  failRate: number | null;
  workerId: string | null;
  lastError: string | null;
  createdAt: number;
  updatedAt: number;
  startedAt: number | null;
  completedAt: number | null;
  nextRetryAt: number | null;
}

interface Slot {
  id: string;
  jobId: number | null;
  since: number;
  endsAt: number;
  processed: number;
  failed: number;
}

interface Computer {
  instance: string;
  alive: boolean;
  diedAt: number;
  slots: Slot[];
}

const rid = () => Math.random().toString(16).slice(2, 6);

class Simulator {
  private jobs = new Map<number, SimJob>();
  private lines: Record<number, number[]> = { 1: [], 2: [], 3: [] };
  private dlq: number[] = [];
  private computers: Computer[] = [];
  private nextId = 1;
  private cursor = 0;
  private doneTotal = 0;
  private failureRate = 0;
  private lastCrash = 0;
  private buckets = new Map<number, number[]>(); // sec -> [submitted, success, retry, dlq, recovered]
  private latencies: [number, number][] = [];
  private pending: JobEvent[] = [];
  private listeners = new Set<(s: Snapshot) => void>();
  private started = false;
  private lastTrickle = 0;

  constructor() {
    for (let c = 0; c < COMPUTERS; c++) this.computers.push(this.newComputer(c));
  }

  private newComputer(index: number): Computer {
    const instance = `computer${index + 1}-${rid()}`;
    return {
      instance, alive: true, diedAt: 0,
      slots: Array.from({ length: THREADS }, (_, t) => ({
        id: `${instance}-t${t}`, jobId: null, since: Date.now(), endsAt: 0, processed: 0, failed: 0,
      })),
    };
  }

  // ---- lifecycle --------------------------------------------------------------------

  subscribe(fn: (s: Snapshot) => void) {
    this.listeners.add(fn);
    this.start();
    fn(this.snapshot([]));
    return () => this.listeners.delete(fn);
  }

  private start() {
    if (this.started) return;
    this.started = true;
    // A little work already in flight so the page is never empty on first visit.
    this.burst(24, 1600);
    setInterval(() => this.step(), 100);
    setInterval(() => {
      const fresh = this.pending.splice(0, this.pending.length).slice(-100);
      const snap = this.snapshot(fresh);
      this.listeners.forEach((fn) => fn(snap));
    }, 1000);
  }

  // ---- the "API" ----------------------------------------------------------------------

  submit(type: string, priority: Priority, maxRetries: number, durationMs = 2500): Job {
    const job = this.create(type, LEVEL[priority], maxRetries, durationMs, null);
    this.event({ type: "SUBMITTED", jobId: job.id, priority: job.priority, count: 1 });
    return this.view(job);
  }

  burst(count: number, durationMs = 1500): { submitted: number } {
    for (let i = 0; i < count; i++) {
      const r = Math.random();
      this.create("demo", r < 0.2 ? 1 : r < 0.5 ? 2 : 3, 3, durationMs, null);
    }
    this.event({ type: "SUBMITTED", count });
    return { submitted: count };
  }

  setFailureRate(rate: number) {
    this.failureRate = Math.max(0, Math.min(0.9, rate));
    return { failureRate: this.failureRate };
  }

  crashWorker(): { instance: string } {
    const now = Date.now();
    if (now - this.lastCrash < 10_000) throw new Error("A computer was crashed recently; wait for it to recover.");
    const alive = this.computers.filter((c) => c.alive);
    if (alive.length === 0) throw new Error("All computers are already restarting.");
    const victim = alive[Math.floor(Math.random() * alive.length)];
    victim.alive = false;
    victim.diedAt = now;
    this.lastCrash = now;
    this.event({ type: "CRASH_REQUESTED", instance: victim.instance });
    return { instance: victim.instance };
  }

  redrive(id: number): { requeued: number } {
    const job = this.jobs.get(id);
    if (!job || job.status !== "DLQ") return { requeued: 0 };
    this.dlq = this.dlq.filter((x) => x !== id);
    Object.assign(job, { status: "QUEUED", retryCount: 0, lastError: null, nextRetryAt: null, updatedAt: Date.now() });
    this.lines[job.priority].push(id);
    this.event({ type: "REDRIVEN", jobId: id, priority: job.priority });
    return { requeued: 1 };
  }

  redriveAll(): { requeued: number } {
    return { requeued: [...this.dlq].reduce((n, id) => n + this.redrive(id).requeued, 0) };
  }

  list(status: JobStatus | "ALL", size: number) {
    const all = [...this.jobs.values()].filter((j) => status === "ALL" || j.status === status);
    all.sort((a, b) => b.id - a.id);
    return { items: all.slice(0, size).map((j) => this.view(j)), totalElements: all.length };
  }

  stats(): Snapshot {
    return this.snapshot([]);
  }

  // ---- simulation step (every 100 ms) -------------------------------------------------

  private step() {
    const now = Date.now();

    // gentle background traffic so the demo keeps moving when the visitor is idle
    if (now - this.lastTrickle > 900 && this.waiting() < 6) {
      this.lastTrickle = now;
      const r = Math.random();
      this.create("demo", r < 0.2 ? 1 : r < 0.5 ? 2 : 3, 3, 1200 + Math.random() * 800, null);
    }

    // retry scheduler: backoff elapsed -> back in line
    for (const job of this.jobs.values()) {
      if (job.status === "RETRYING" && job.nextRetryAt !== null && job.nextRetryAt <= now) {
        Object.assign(job, { status: "QUEUED", nextRetryAt: null, updatedAt: now });
        this.lines[job.priority].push(job.id);
      }
    }

    // crashed computers: reaper rescues their jobs after the heartbeat goes stale; then restart
    this.computers.forEach((c, i) => {
      if (c.alive) return;
      if (now - c.diedAt >= STALE_MS) {
        for (const s of c.slots) {
          if (s.jobId === null) continue;
          const job = this.jobs.get(s.jobId)!;
          Object.assign(job, {
            status: "QUEUED", workerId: null, recoveredCount: job.recoveredCount + 1,
            lastError: "worker heartbeat lost; job reclaimed", updatedAt: now,
          });
          this.lines[job.priority].push(job.id);
          this.event({ type: "RECOVERED", jobId: job.id, priority: job.priority, reason: "stale_heartbeat" });
          s.jobId = null;
        }
      }
      if (now - c.diedAt >= Math.max(RESTART_MS, STALE_MS)) this.computers[i] = this.newComputer(i);
    });

    // live workers: finish work, then pick up more
    for (const c of this.computers) {
      if (!c.alive) continue;
      for (const s of c.slots) {
        if (s.jobId !== null && now >= s.endsAt) this.finish(s, now);
        if (s.jobId === null) this.pickUp(s, now);
      }
    }

    // keep memory small: forget long-finished jobs (the Done total is kept separately)
    for (const job of this.jobs.values()) {
      if (job.status === "SUCCESS" && now - (job.completedAt ?? now) > KEEP_FINISHED_MS) this.jobs.delete(job.id);
    }
  }

  private pickUp(s: Slot, now: number) {
    const preferred = SLOTS[this.cursor++ % SLOTS.length];
    const order = [preferred, ...[1, 2, 3].filter((p) => p !== preferred)];
    for (const p of order) {
      const id = this.lines[p].shift();
      if (id === undefined) continue;
      const job = this.jobs.get(id);
      if (!job || job.status !== "QUEUED") continue; // same as the real conditional claim
      Object.assign(job, {
        status: "PROCESSING", workerId: s.id, attempts: job.attempts + 1, startedAt: now, updatedAt: now,
      });
      s.jobId = id;
      s.since = now;
      s.endsAt = now + job.durationMs * (0.8 + 0.4 * Math.random());
      return;
    }
  }

  private finish(s: Slot, now: number) {
    const job = this.jobs.get(s.jobId!)!;
    s.jobId = null;
    s.since = now;
    const rate = job.failRate ?? this.failureRate;
    if (Math.random() >= rate) {
      Object.assign(job, { status: "SUCCESS", completedAt: now, updatedAt: now, lastError: null });
      s.processed++;
      this.doneTotal++;
      const latencyMs = now - job.createdAt;
      this.latencies.push([now, latencyMs]);
      this.event({ type: "SUCCESS", jobId: job.id, priority: job.priority, workerId: s.id, attempt: job.attempts, latencyMs });
      return;
    }
    s.failed++;
    const error = `simulated transient failure (failRate=${rate})`;
    if (job.retryCount < job.maxRetries) {
      const retry = job.retryCount + 1;
      const delayMs = Math.round(Math.random() * Math.min(RETRY_CAP_MS, RETRY_BASE_MS * 2 ** retry));
      Object.assign(job, {
        status: "RETRYING", retryCount: retry, nextRetryAt: now + delayMs, lastError: error, workerId: null, updatedAt: now,
      });
      this.event({ type: "RETRY", jobId: job.id, priority: job.priority, workerId: s.id, attempt: job.attempts, retry, delayMs, error });
    } else {
      Object.assign(job, { status: "DLQ", completedAt: now, lastError: error, updatedAt: now });
      this.dlq.push(job.id);
      this.event({ type: "DLQ", jobId: job.id, priority: job.priority, workerId: s.id, attempt: job.attempts, error });
    }
  }

  // ---- helpers ------------------------------------------------------------------------

  private create(type: string, priority: number, maxRetries: number, durationMs: number, failRate: number | null) {
    const now = Date.now();
    const job: SimJob = {
      id: this.nextId++, type, priority, status: "QUEUED", retryCount: 0, maxRetries, attempts: 0,
      recoveredCount: 0, durationMs, failRate, workerId: null, lastError: null,
      createdAt: now, updatedAt: now, startedAt: null, completedAt: null, nextRetryAt: null,
    };
    this.jobs.set(job.id, job);
    this.lines[priority].push(job.id);
    this.bucket(now)[0]++;
    return job;
  }

  private waiting() {
    return this.lines[1].length + this.lines[2].length + this.lines[3].length;
  }

  private bucket(ts: number) {
    const sec = Math.floor(ts / 1000);
    let b = this.buckets.get(sec);
    if (!b) this.buckets.set(sec, (b = [0, 0, 0, 0, 0]));
    return b;
  }

  private event(e: Omit<JobEvent, "ts">) {
    const ev = { ts: Date.now(), ...e } as JobEvent;
    const b = this.bucket(ev.ts);
    if (ev.type === "SUCCESS") b[1]++;
    if (ev.type === "RETRY") b[2]++;
    if (ev.type === "DLQ") b[3]++;
    if (ev.type === "RECOVERED") b[4]++;
    this.pending.push(ev);
  }

  private view(j: SimJob): Job {
    const iso = (t: number | null) => (t === null ? null : new Date(t).toISOString());
    return {
      id: j.id, type: j.type, status: j.status, priority: PRIORITY_NAME[j.priority],
      retryCount: j.retryCount, maxRetries: j.maxRetries, attempts: j.attempts, recoveredCount: j.recoveredCount,
      workerId: j.workerId, lastError: j.lastError, createdAt: new Date(j.createdAt).toISOString(),
      completedAt: iso(j.completedAt), nextRetryAt: iso(j.nextRetryAt),
    };
  }

  private snapshot(events: JobEvent[]): Snapshot {
    const now = Date.now();
    const nowSec = Math.floor(now / 1000);
    const statuses: Record<JobStatus, number> = { QUEUED: 0, PROCESSING: 0, RETRYING: 0, SUCCESS: this.doneTotal, DLQ: 0 };
    for (const j of this.jobs.values()) if (j.status !== "SUCCESS") statuses[j.status]++;

    const series: SeriesPoint[] = [];
    let success10 = 0;
    for (let sec = nowSec - 60; sec < nowSec; sec++) {
      const b = this.buckets.get(sec) ?? [0, 0, 0, 0, 0];
      series.push({ t: sec * 1000, submitted: b[0], success: b[1], retry: b[2], dlq: b[3], recovered: b[4] });
      if (sec >= nowSec - 10) success10 += b[1];
    }
    for (const sec of this.buckets.keys()) if (sec < nowSec - 120) this.buckets.delete(sec);

    this.latencies = this.latencies.filter(([t]) => t >= now - 60_000);
    const sorted = this.latencies.map(([, l]) => l).sort((a, b) => a - b);
    const pct = (q: number) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1))] : null);

    const workers: WorkerInfo[] = this.computers.filter((c) => c.alive).flatMap((c) =>
      c.slots.map((s) => {
        const job = s.jobId === null ? null : this.jobs.get(s.jobId);
        return {
          id: s.id, instance: c.instance, state: job ? "BUSY" : "IDLE",
          jobId: job ? String(job.id) : "", priority: job ? String(job.priority) : "",
          since: String(s.since), processed: String(s.processed), failed: String(s.failed), heartbeat: String(now),
        } as WorkerInfo;
      }));

    return {
      ts: now,
      queues: { HIGH: this.lines[1].length, MEDIUM: this.lines[2].length, LOW: this.lines[3].length, DLQ: this.dlq.length },
      statuses,
      workers,
      series,
      throughputPerSec: success10 / 10,
      latencyMs: { p50: pct(0.5), p95: pct(0.95), p99: pct(0.99), samples: sorted.length },
      failureRate: this.failureRate,
      events,
    };
  }
}

export const simulator = new Simulator();
