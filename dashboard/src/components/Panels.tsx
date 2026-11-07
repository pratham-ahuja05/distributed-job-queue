import type { JobEvent, Snapshot, WorkerInfo } from "../types";

const PRIORITY_NAME: Record<string, string> = { "1": "HIGH", "2": "MEDIUM", "3": "LOW" };

export function fmt(n: number | null | undefined, digits = 0) {
  if (n === null || n === undefined || Number.isNaN(n)) return "–";
  return n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

export function fmtMs(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return { value: "–", unit: "" };
  return ms >= 1000 ? { value: (ms / 1000).toFixed(2), unit: "s" } : { value: String(Math.round(ms)), unit: "ms" };
}

// ---- stat tiles -------------------------------------------------------------------

export function StatTiles({ s }: { s: Snapshot }) {
  const waiting = s.queues.HIGH + s.queues.MEDIUM + s.queues.LOW;
  const p95 = fmtMs(s.latencyMs.p95);
  const busy = s.workers.filter((w) => w.state === "BUSY").length;
  const tiles = [
    { label: "Throughput", value: fmt(s.throughputPerSec, 1), unit: "jobs/s", hint: "completed, 10 s average" },
    { label: "p95 latency", value: p95.value, unit: p95.unit, hint: `submit → done, last 60 s` },
    { label: "Waiting", value: fmt(waiting), unit: "", hint: "IDs queued in Redis" },
    { label: "Workers busy", value: `${busy}`, unit: `/ ${s.workers.length}`, hint: "threads running a job" },
    { label: "Retrying", value: fmt(s.statuses.RETRYING), unit: "", hint: "in backoff" },
    { label: "Completed", value: fmt(s.statuses.SUCCESS), unit: "", hint: "all time (retained)" },
    { label: "Dead-lettered", value: fmt(s.statuses.DLQ), unit: "", hint: "retries exhausted" },
  ];
  return (
    <section className="grid tiles" aria-label="Key metrics">
      {tiles.map((t) => (
        <div key={t.label} className="card tile">
          <div className="label">{t.label}</div>
          <div className="value">{t.value}{t.unit && <small>{t.unit}</small>}</div>
          <div className="hint">{t.hint}</div>
        </div>
      ))}
    </section>
  );
}

// ---- queues ------------------------------------------------------------------------

export function QueueBars({ queues }: { queues: Snapshot["queues"] }) {
  const max = Math.max(10, queues.HIGH, queues.MEDIUM, queues.LOW, queues.DLQ);
  const row = (name: string, value: number, dlq = false) => (
    <div className="qbar" key={name}>
      <span className="name">{name}</span>
      <div className="track" role="presentation">
        <div className={`fill${dlq ? " dlq" : ""}`} style={{ width: `${(value / max) * 100}%` }} />
      </div>
      <span className="num">{fmt(value)}</span>
    </div>
  );
  return (
    <div className="card">
      <h2>Redis queues</h2>
      <p className="sub">Job IDs waiting per priority. Workers pull 6 : 3 : 1 so LOW never starves.</p>
      {row("HIGH", queues.HIGH)}
      {row("MEDIUM", queues.MEDIUM)}
      {row("LOW", queues.LOW)}
      <div className="qdivider" />
      {row("DLQ", queues.DLQ, true)}
    </div>
  );
}

// ---- workers -----------------------------------------------------------------------

function age(ms: number) {
  const s = Math.max(0, Math.round(ms / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

export function WorkerGrid({ workers, now }: { workers: WorkerInfo[]; now: number }) {
  const byInstance = new Map<string, WorkerInfo[]>();
  for (const w of workers) {
    const list = byInstance.get(w.instance) ?? [];
    list.push(w);
    byInstance.set(w.instance, list);
  }
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Workers</h2>
          <p className="sub">
            {byInstance.size} container{byInstance.size === 1 ? "" : "s"} × threads. A crashed container
            disappears here; Docker restarts it under a new identity.
          </p>
        </div>
      </div>
      {workers.length === 0 ? (
        <div className="empty">No live workers - waiting for heartbeats…</div>
      ) : (
        <div className="containers">
          {[...byInstance.entries()].map(([instance, threads]) => {
            const done = threads.reduce((n, t) => n + Number(t.processed || 0), 0);
            return (
              <div key={instance} className="container">
                <div className="container-head">
                  <code title={instance}>{instance}</code>
                  <span>{fmt(done)} done</span>
                </div>
                <div className="threads">
                  {threads.map((t) => {
                    const busy = t.state === "BUSY";
                    return (
                      <div key={t.id} className={`thread${busy ? " busy" : ""}`}>
                        <div className="top">
                          <span className="state"><i />{busy ? "Busy" : "Idle"}</span>
                          <span className="meta">{t.id.slice(t.id.lastIndexOf("-") + 1)}</span>
                        </div>
                        <div className="meta">
                          {busy
                            ? <>job #{t.jobId} · {PRIORITY_NAME[t.priority] ?? "?"} · {age(now - Number(t.since))}</>
                            : <>{fmt(Number(t.processed))} ok · {fmt(Number(t.failed))} failed</>}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ---- event feed --------------------------------------------------------------------

const EVENT_STYLE: Record<string, { icon: string; label: string; tone: string }> = {
  SUCCESS: { icon: "✓", label: "Completed", tone: "good" },
  RETRY: { icon: "↻", label: "Retry", tone: "warning" },
  DLQ: { icon: "✕", label: "Dead-letter", tone: "critical" },
  RECOVERED: { icon: "⟲", label: "Recovered", tone: "info" },
  REDRIVEN: { icon: "↺", label: "Re-driven", tone: "info" },
  SUBMITTED: { icon: "+", label: "Submitted", tone: "neutral" },
  CRASH_REQUESTED: { icon: "⚡", label: "Crash", tone: "critical" },
};

function describe(e: JobEvent) {
  const prio = e.priority ? ` · ${PRIORITY_NAME[String(e.priority)]}` : "";
  switch (e.type) {
    case "SUCCESS": return `#${e.jobId}${prio} in ${fmtMs(e.latencyMs).value}${fmtMs(e.latencyMs).unit} (attempt ${e.attempt})`;
    case "RETRY": return `#${e.jobId}${prio} retry ${e.retry} in ${fmtMs(e.delayMs).value}${fmtMs(e.delayMs).unit} (jittered)`;
    case "DLQ": return `#${e.jobId}${prio} after ${e.attempt} attempts`;
    case "RECOVERED": return `#${e.jobId}${prio} · ${String(e.reason).replace(/_/g, " ")}`;
    case "REDRIVEN": return `#${e.jobId}${prio} back to queue`;
    case "SUBMITTED": return e.count && e.count > 1 ? `${e.count} jobs` : `#${e.jobId}${prio}`;
    case "CRASH_REQUESTED": return `halting ${e.instance}`;
    default: return "";
  }
}

export function EventFeed({ events }: { events: JobEvent[] }) {
  return (
    <div className="card">
      <h2>Live events</h2>
      <p className="sub">Published by workers over Redis pub/sub, streamed to you via SSE.</p>
      {events.length === 0 ? (
        <div className="empty">Quiet. Send a burst to see jobs flow.</div>
      ) : (
        <ul className="feed">
          {events.map((e, i) => {
            const st = EVENT_STYLE[e.type] ?? { icon: "•", label: e.type, tone: "neutral" };
            return (
              <li key={`${e.ts}-${e.type}-${e.jobId ?? i}-${i}`}>
                <span className={`badge ${st.tone}`}><span aria-hidden>{st.icon}</span>{st.label}</span>
                <span className="detail" title={e.error ?? ""}>{describe(e)}</span>
                <span className="when">{new Date(e.ts).toLocaleTimeString()}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
