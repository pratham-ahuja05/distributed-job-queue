import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { Priority } from "../types";

type Toast = { text: string; error?: boolean } | null;

export function Controls({ failureRate }: { failureRate: number }) {
  const [priority, setPriority] = useState<Priority>("HIGH");
  const [count, setCount] = useState(100);
  const [rate, setRate] = useState(Math.round(failureRate * 100));
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<Toast>(null);
  const editing = useRef(false);
  const debounce = useRef<number | null>(null);

  // Follow the server value (another viewer may change it) unless the user is dragging.
  useEffect(() => {
    if (!editing.current) setRate(Math.round(failureRate * 100));
  }, [failureRate]);

  const run = async (key: string, fn: () => Promise<string>) => {
    setBusy(key);
    try {
      setToast({ text: await fn() });
    } catch (e) {
      setToast({ text: (e as Error).message, error: true });
    } finally {
      setBusy(null);
    }
  };

  const onRate = (value: number) => {
    editing.current = true;
    setRate(value);
    if (debounce.current) window.clearTimeout(debounce.current);
    debounce.current = window.setTimeout(() => {
      void run("rate", async () => {
        const res = await api.setFailureRate(value / 100);
        editing.current = false;
        return `Workers now fail ${Math.round(res.failureRate * 100)}% of attempts.`;
      });
    }, 300);
  };

  return (
    <div className="card">
      <h2>Try it</h2>
      <p className="sub">Everything you do here hits the real system.</p>

      <div className="control">
        <div className="control-title">Submit one job</div>
        <p className="control-help">Persisted in Postgres, its ID pushed to the matching Redis queue.</p>
        <div className="inline">
          <div className="seg" role="group" aria-label="Priority">
            {(["HIGH", "MEDIUM", "LOW"] as Priority[]).map((p) => (
              <button key={p} aria-pressed={priority === p} onClick={() => setPriority(p)}>{p}</button>
            ))}
          </div>
          <button className="btn primary" disabled={busy !== null}
            onClick={() => run("one", async () => {
              const job = await api.submit("demo", priority, 3);
              return `Job #${job.id} queued (${job.priority}).`;
            })}>
            Submit
          </button>
        </div>
      </div>

      <div className="control">
        <div className="control-title">Send a burst</div>
        <p className="control-help">Mixed priorities (20% HIGH, 30% MEDIUM, 50% LOW), ~400 ms of work each.</p>
        <div className="range">
          <input type="range" min={10} max={300} step={10} value={count}
            onChange={(e) => setCount(Number(e.target.value))} aria-label="Burst size" />
          <output>{count}</output>
        </div>
        <div className="inline" style={{ marginTop: 8 }}>
          <button className="btn primary" disabled={busy !== null}
            onClick={() => run("burst", async () => {
              const res = await api.burst(count, 400);
              return `${res.submitted} jobs submitted. Watch the queues drain.`;
            })}>
            Send {count} jobs
          </button>
        </div>
      </div>

      <div className="control">
        <div className="control-title">Inject failures</div>
        <p className="control-help">Chance each attempt throws. Failed jobs back off (exponential, full jitter); after 3 retries they go to the DLQ.</p>
        <div className="range">
          <input type="range" min={0} max={90} step={5} value={rate}
            onChange={(e) => onRate(Number(e.target.value))} aria-label="Failure rate" />
          <output>{rate}%</output>
        </div>
      </div>

      <div className="control">
        <div className="control-title">Crash a worker</div>
        <p className="control-help">Halts one worker JVM mid-job (like kill -9). Its jobs are reclaimed via heartbeat timeout and finish elsewhere - none are lost.</p>
        <button className="btn danger" disabled={busy !== null}
          onClick={() => run("crash", async () => {
            const res = await api.crashWorker();
            return `Crashed ${res.instance}. Recovery kicks in after ~15 s.`;
          })}>
          ⚡ Crash a random worker
        </button>
      </div>

      <div className={`toast${toast?.error ? " error" : ""}`} role="status" aria-live="polite">{toast?.text}</div>
    </div>
  );
}
