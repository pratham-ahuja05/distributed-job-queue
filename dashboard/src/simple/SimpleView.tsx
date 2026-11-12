import { useMemo, useState } from "react";
import { api } from "../api";
import type { JobEvent, Priority, Snapshot, WorkerInfo } from "../types";
import "./simple.css";

/* Plain-language names, used everywhere in this view. */
const LANES: { key: Priority; level: string; name: string; hint: string }[] = [
  { key: "HIGH", level: "1", name: "Urgent", hint: "picked first" },
  { key: "MEDIUM", level: "2", name: "Normal", hint: "picked next" },
  { key: "LOW", level: "3", name: "Low", hint: "still gets a turn" },
];
const NAME_BY_LEVEL: Record<string, string> = { "1": "Urgent", "2": "Normal", "3": "Low" };
const CLASS_BY_LEVEL: Record<string, string> = { "1": "p-urgent", "2": "p-normal", "3": "p-low" };

const MAX_TOKENS = 18;

function recent(series: Snapshot["series"], key: keyof Snapshot["series"][number], seconds = 5) {
  return series.slice(-seconds).reduce((n, p) => n + (p[key] as number), 0);
}

// ---------------------------------------------------------------------------------------

export function SimpleView({ s, events }: { s: Snapshot; events: JobEvent[] }) {
  return (
    <div className="sv">
      <Intro />
      <Actions failureRate={s.failureRate} dead={s.statuses.DLQ} />
      <Factory s={s} events={events} />
      <div className="sv-bottom">
        <Journey s={s} />
        <Story events={events} />
      </div>
    </div>
  );
}

// ---- 1. what is this? ------------------------------------------------------------------

function Intro() {
  return (
    <section className="sv-intro">
      <h2>Tasks wait in line, workers pick them up, and nothing gets lost.</h2>
      <ol>
        <li><b>Wait in line.</b> Every task joins one of three lines: Urgent, Normal or Low.</li>
        <li><b>Get picked up.</b> 8 workers grab tasks, urgent ones first, but every line gets a turn.</li>
        <li><b>Finish or retry.</b> If something goes wrong, the task tries again a bit later. After 4 failed tries it's set aside.</li>
      </ol>
    </section>
  );
}

// ---- 2. buttons ------------------------------------------------------------------------

function Actions({ failureRate, dead }: { failureRate: number; dead: number }) {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ text: string; error?: boolean } | null>(null);
  const failing = failureRate > 0;

  const run = async (fn: () => Promise<string>) => {
    setBusy(true);
    try {
      setNote({ text: await fn() });
    } catch (e) {
      setNote({ text: (e as Error).message, error: true });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="sv-actions" aria-label="Try it">
      <div className="sv-act-group">
        <span className="sv-act-label">Add a task</span>
        {LANES.map((l) => (
          <button key={l.key} className={`sv-btn add ${CLASS_BY_LEVEL[l.level]}`} disabled={busy}
            onClick={() => run(async () => {
              const job = await api.submit("demo", l.key, 3, { durationMs: 2500 });
              return `Task #${job.id} joined the ${l.name} line.`;
            })}>
            <span className="tok" aria-hidden /> {l.name}
          </button>
        ))}
        <button className="sv-btn primary" disabled={busy}
          onClick={() => run(async () => {
            const r = await api.burst(40, 1500);
            return `${r.submitted} tasks added across all three lines. Watch the workers clear them.`;
          })}>
          Add 40 tasks
        </button>
      </div>

      <div className="sv-act-group">
        <label className="sv-switch">
          <input type="checkbox" checked={failing} disabled={busy}
            onChange={(e) => run(async () => {
              const r = await api.setFailureRate(e.target.checked ? 0.3 : 0);
              return r.failureRate > 0
                ? "Now about 1 in 3 attempts fails. Watch tasks retry instead of being lost."
                : "Failures switched off.";
            })} />
          <span className="track" aria-hidden><span className="thumb" /></span>
          Make things go wrong sometimes
        </label>
        <button className="sv-btn danger" disabled={busy}
          onClick={() => run(async () => {
            await api.crashWorker();
            return "A computer just crashed mid-work. In ~15 s its tasks go back in line and finish elsewhere.";
          })}>
          ⚡ Crash a computer
        </button>
        {dead > 0 && (
          <button className="sv-btn" disabled={busy}
            onClick={() => run(async () => `${(await api.redriveAll()).requeued} set-aside tasks put back in line.`)}>
            ↺ Retry set-aside tasks
          </button>
        )}
      </div>
      <p className={`sv-note${note?.error ? " error" : ""}${note ? "" : " empty"}`} role="status" aria-live="polite">{note?.text}</p>
    </section>
  );
}

// ---- 3. the main picture: lines → workers → results -------------------------------------

function Factory({ s, events }: { s: Snapshot; events: JobEvent[] }) {
  const machines = useMemo(() => {
    const byInstance = new Map<string, WorkerInfo[]>();
    for (const w of s.workers) {
      byInstance.set(w.instance, [...(byInstance.get(w.instance) ?? []), w]);
    }
    return [...byInstance.values()];
  }, [s.workers]);

  const picked = recent(s.series, "success") + recent(s.series, "retry") + recent(s.series, "dlq");
  const done = events.filter((e) => e.type === "SUCCESS").slice(0, MAX_TOKENS);

  return (
    <section className="sv-factory" aria-label="Live view">
      {/* lines */}
      <div className="sv-col">
        <h3>1 · Waiting in line</h3>
        {LANES.map((l) => {
          const n = s.queues[l.key];
          return (
            <div key={l.key} className={`sv-lane ${CLASS_BY_LEVEL[l.level]}`}>
              <div className="sv-lane-head">
                <span className="name">{l.name}</span>
                <span className="hint">{l.hint}</span>
                <span className="count">{n}</span>
              </div>
              <div className="sv-lane-track" aria-label={`${n} ${l.name} tasks waiting`}>
                {n === 0 && <span className="sv-empty">empty</span>}
                {Array.from({ length: Math.min(n, MAX_TOKENS) }).map((_, i) => <span key={i} className="tok" />)}
                {n > MAX_TOKENS && <span className="more">+{n - MAX_TOKENS}</span>}
              </div>
            </div>
          );
        })}
      </div>

      <Arrow label={picked > 0 ? `${picked} picked up` : "picked up"} active={picked > 0} />

      {/* workers */}
      <div className="sv-col">
        <h3>2 · Being worked on</h3>
        {machines.length === 0 && <div className="sv-machine empty">All computers are restarting…</div>}
        {machines.map((m, i) => (
          <div key={m[0].instance} className="sv-machine">
            <div className="sv-machine-head">
              <span>Computer {i + 1}</span>
              <span className="muted">{m.filter((w) => w.state === "BUSY").length} of {m.length} busy</span>
            </div>
            <div className="sv-slots">
              {m.map((w) => {
                const busy = w.state === "BUSY";
                return (
                  <div key={w.id} className={`sv-slot${busy ? ` busy ${CLASS_BY_LEVEL[w.priority] ?? ""}` : ""}`}>
                    {busy ? (
                      <>
                        <span className="tok spin" aria-hidden />
                        <span className="what">#{w.jobId}</span>
                        <span className="sub">{NAME_BY_LEVEL[w.priority]}</span>
                      </>
                    ) : (
                      <span className="sub">free</span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <Arrow label="result" active={picked > 0} />

      {/* outcomes */}
      <div className="sv-col">
        <h3>3 · Result</h3>
        <div className="sv-bin good">
          <div className="sv-bin-head"><span className="icon">✓</span><span>Done</span><b>{s.statuses.SUCCESS.toLocaleString()}</b></div>
          <div className="sv-bin-track">
            {done.map((e, i) => <span key={`${e.jobId}-${i}`} className={`tok ${CLASS_BY_LEVEL[String(e.priority)]}`} title={`#${e.jobId}`} />)}
          </div>
        </div>
        <div className="sv-bin warn">
          <div className="sv-bin-head"><span className="icon">↻</span><span>Will retry soon</span><b>{s.statuses.RETRYING}</b></div>
          <p>Something went wrong. Waiting a moment, then back in line.</p>
        </div>
        <div className="sv-bin bad">
          <div className="sv-bin-head"><span className="icon">✕</span><span>Gave up</span><b>{s.statuses.DLQ}</b></div>
          <p>Failed 4 times. Set aside so a person can look, never deleted.</p>
        </div>
      </div>
    </section>
  );
}

function Arrow({ label, active }: { label: string; active: boolean }) {
  return (
    <div className={`sv-arrow${active ? " active" : ""}`} aria-hidden>
      <span className="line" />
      <span className="lbl">{label}</span>
    </div>
  );
}

// ---- 4. a task's journey (state diagram with live counts) -------------------------------

function Journey({ s }: { s: Snapshot }) {
  const pulse = {
    pick: recent(s.series, "success") + recent(s.series, "retry") + recent(s.series, "dlq") > 0,
    done: recent(s.series, "success") > 0,
    retry: recent(s.series, "retry") > 0,
    dead: recent(s.series, "dlq") > 0,
    rescued: recent(s.series, "recovered", 20) > 0,
  };
  const st = s.statuses;
  const node = (x: number, y: number, w: number, label: string, count: number, cls: string, icon: string) => (
    <g className={`jn ${cls}`} transform={`translate(${x},${y})`}>
      <rect width={w} height="58" rx="12" />
      <text x={w / 2} y="24" className="jn-label">{icon} {label}</text>
      <text x={w / 2} y="45" className="jn-count">{count.toLocaleString()}</text>
    </g>
  );
  return (
    <section className="sv-card sv-journey">
      <h3>A task's journey</h3>
      <p className="muted">Every task moves through these steps. Arrows light up as it happens.</p>
      <svg viewBox="0 0 640 260" role="img"
        aria-label={`Waiting ${st.QUEUED}, working ${st.PROCESSING}, done ${st.SUCCESS}, will retry ${st.RETRYING}, gave up ${st.DLQ}`}>
        <defs>
          <marker id="jh" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="jh" />
          </marker>
        </defs>
        {/* edges */}
        <path d="M150 99 H232" className={`je${pulse.pick ? " on" : ""}`} markerEnd="url(#jh)" />
        <text x="191" y="88" className="je-lbl">picked up</text>
        <path d="M382 99 H464" className={`je${pulse.done ? " on good" : ""}`} markerEnd="url(#jh)" />
        <text x="423" y="88" className="je-lbl">finished</text>
        <path d="M330 128 C 330 180 430 205 462 205" className={`je${pulse.dead ? " on bad" : ""}`} markerEnd="url(#jh)" />
        <text x="400" y="172" className="je-lbl">failed 4×</text>
        <path d="M284 128 C 284 175 200 205 150 205" className={`je${pulse.retry ? " on warn" : ""}`} markerEnd="url(#jh)" />
        <text x="205" y="172" className="je-lbl">failed, try later</text>
        <path d="M76 176 V132" className={`je${pulse.retry ? " on warn" : ""}`} markerEnd="url(#jh)" />
        <text x="84" y="158" className="je-lbl start">back in line</text>
        <path d="M262 70 C 262 20 120 20 104 66" className={`je dashed${pulse.rescued ? " on info" : ""}`} markerEnd="url(#jh)" />
        <text x="183" y="22" className="je-lbl">computer crashed → rescued</text>
        {/* nodes */}
        {node(10, 70, 140, "Waiting", st.QUEUED, "n-wait", "⏳")}
        {node(236, 70, 146, "Working", st.PROCESSING, "n-work", "⚙")}
        {node(468, 70, 162, "Done", st.SUCCESS, "n-done", "✓")}
        {node(10, 178, 140, "Will retry", st.RETRYING, "n-retry", "↻")}
        {node(468, 178, 162, "Gave up", st.DLQ, "n-dead", "✕")}
      </svg>
    </section>
  );
}

// ---- 5. what just happened (plain English) ----------------------------------------------

function sentence(e: JobEvent): { icon: string; tone: string; text: string } | null {
  const who = e.priority ? `${NAME_BY_LEVEL[String(e.priority)]} task #${e.jobId}` : `Task #${e.jobId}`;
  const secs = (ms?: number) => (ms === undefined ? "" : ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`);
  switch (e.type) {
    case "SUCCESS":
      return { icon: "✓", tone: "good", text: `${who} finished${e.attempt && e.attempt > 1 ? ` on try ${e.attempt}` : ""} (${secs(e.latencyMs)} after it was added).` };
    case "RETRY":
      return { icon: "↻", tone: "warn", text: `${who} hit a problem. It will try again in ${secs(e.delayMs)}.` };
    case "DLQ":
      return { icon: "✕", tone: "bad", text: `${who} failed 4 times and was set aside.` };
    case "RECOVERED":
      return { icon: "⛑", tone: "info", text: `${who} was rescued from a crashed computer and put back in line.` };
    case "REDRIVEN":
      return { icon: "↺", tone: "info", text: `${who} was put back in line for another go.` };
    case "SUBMITTED":
      return { icon: "+", tone: "muted", text: e.count && e.count > 1 ? `${e.count} new tasks joined the lines.` : `${who} joined the line.` };
    case "CRASH_REQUESTED":
      return { icon: "⚡", tone: "bad", text: "A computer was crashed on purpose. Its tasks will be rescued." };
    default:
      return null;
  }
}

function Story({ events }: { events: JobEvent[] }) {
  const lines = events.map((e) => ({ e, s: sentence(e) })).filter((x) => x.s).slice(0, 9);
  return (
    <section className="sv-card sv-story">
      <h3>What just happened</h3>
      {lines.length === 0 ? (
        <p className="muted">Nothing yet. Press <b>Add 40 tasks</b> above.</p>
      ) : (
        <ul>
          {lines.map(({ e, s: line }, i) => (
            <li key={`${e.ts}-${e.type}-${e.jobId ?? i}-${i}`} className={line!.tone}>
              <span className="ic" aria-hidden>{line!.icon}</span>
              <span>{line!.text}</span>
              <time>{new Date(e.ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
