import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import type { Job, JobStatus } from "../types";

const FILTERS: (JobStatus | "ALL")[] = ["ALL", "PROCESSING", "RETRYING", "DLQ", "SUCCESS", "QUEUED"];

const STATUS: Record<JobStatus, { icon: string; label: string; tone: string }> = {
  QUEUED: { icon: "…", label: "Queued", tone: "neutral" },
  PROCESSING: { icon: "▶", label: "Processing", tone: "info" },
  RETRYING: { icon: "↻", label: "Retrying", tone: "warning" },
  SUCCESS: { icon: "✓", label: "Success", tone: "good" },
  DLQ: { icon: "✕", label: "DLQ", tone: "critical" },
};

export function JobsTable({ tick }: { tick: number }) {
  const [filter, setFilter] = useState<JobStatus | "ALL">("ALL");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const page = await api.jobs(filter);
      setJobs(page.items);
      setTotal(page.totalElements);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [filter]);

  // Refresh every ~2 snapshots, and immediately when the filter changes.
  useEffect(() => {
    if (tick % 2 === 0) void load();
  }, [tick, load]);
  useEffect(() => {
    void load();
  }, [load]);

  const redrive = async (id?: number) => {
    try {
      if (id === undefined) await api.redriveAll();
      else await api.redrive(id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Jobs</h2>
          <p className="sub">Newest first, straight from Postgres · {total.toLocaleString()} matching</p>
        </div>
        {filter === "DLQ" && jobs.length > 0 && (
          <button className="btn small" onClick={() => redrive()}>Re-drive all</button>
        )}
      </div>
      <div className="tabs" role="group" aria-label="Filter by status">
        {FILTERS.map((f) => (
          <button key={f} aria-pressed={filter === f} onClick={() => setFilter(f)}>
            {f === "ALL" ? "All" : STATUS[f].label}
          </button>
        ))}
      </div>
      {error && <div className="toast error">{error}</div>}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>ID</th><th>Status</th><th>Priority</th><th className="num">Attempts</th>
              <th>Worker</th><th>Last error</th><th />
            </tr>
          </thead>
          <tbody>
            {jobs.map((j) => {
              const st = STATUS[j.status];
              return (
                <tr key={j.id}>
                  <td>#{j.id}</td>
                  <td><span className={`badge ${st.tone}`}><span aria-hidden>{st.icon}</span>{st.label}</span></td>
                  <td><span className={`prio ${j.priority}`}>{j.priority}</span></td>
                  <td className="num">
                    {j.attempts}
                    {j.recoveredCount > 0 && <span title="reclaimed after a worker crash" style={{ color: "var(--info-text)" }}> ⟲</span>}
                  </td>
                  <td style={{ color: "var(--text-muted)" }}>{j.workerId ? j.workerId.slice(-10) : "–"}</td>
                  <td className="err" title={j.lastError ?? ""}>{j.lastError ?? ""}</td>
                  <td>{j.status === "DLQ" && <button className="btn small" onClick={() => redrive(j.id)}>Re-drive</button>}</td>
                </tr>
              );
            })}
            {jobs.length === 0 && (
              <tr><td colSpan={7} className="empty">No jobs here yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
