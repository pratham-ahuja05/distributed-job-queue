import { useEffect, useState } from "react";
import { useLiveStats } from "./useLiveStats";
import { ThroughputChart } from "./components/ThroughputChart";
import { EventFeed, QueueBars, StatTiles, WorkerGrid } from "./components/Panels";
import { Controls } from "./components/Controls";
import { JobsTable } from "./components/JobsTable";

const REPO_URL = import.meta.env.VITE_REPO_URL as string | undefined;

export default function App() {
  const { snapshot, events, connection } = useLiveStats();
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (snapshot) setTick((t) => t + 1);
  }, [snapshot]);

  const connLabel = { connecting: "Connecting…", live: "Live", polling: "Live (polling)", offline: "Offline" }[connection];

  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <h1>Distributed Job Queue</h1>
          <p>Redis priority dispatch · Postgres state · crash-safe workers · retries with jitter · dead-letter queue</p>
        </div>
        <div className="header-actions">
          <span className={`conn ${connection}`}><span className="dot" />{connLabel}</span>
          <a className="link-btn" href="/grafana/d/job-queue" target="_blank" rel="noreferrer">Grafana ↗</a>
          {REPO_URL && <a className="link-btn" href={REPO_URL} target="_blank" rel="noreferrer">Source ↗</a>}
        </div>
      </header>

      {!snapshot ? (
        <div className="card empty">
          {connection === "offline" ? "Can't reach the API. Is the stack running?" : "Connecting to the live stream…"}
        </div>
      ) : (
        <>
          <StatTiles s={snapshot} />

          <section className="grid row-2">
            <Controls failureRate={snapshot.failureRate} />
            <div className="card">
              <h2>Throughput</h2>
              <p className="sub">Jobs per second, last 60 seconds</p>
              <ThroughputChart series={snapshot.series} />
            </div>
          </section>

          <section className="grid row-3">
            <WorkerGrid workers={snapshot.workers} now={snapshot.ts} />
            <QueueBars queues={snapshot.queues} />
          </section>

          <section className="grid row-4">
            <EventFeed events={events} />
            <JobsTable tick={tick} />
          </section>
        </>
      )}

      <section className="card how">
        <h2>How it works</h2>
        <p className="sub">Redis moves job IDs fast; Postgres decides what actually happened.</p>
        <div className="flow">
          <div><b>Submit</b>The API commits the job row, then pushes its ID onto the HIGH, MEDIUM or LOW Redis list.</div>
          <div><b>Pop atomically</b>A Lua script moves the ID into the worker thread's own processing list, so a crash can't drop it.</div>
          <div><b>Claim</b><code>UPDATE … WHERE status='QUEUED'</code>: exactly one worker wins, so duplicate IDs are harmless.</div>
          <div><b>Execute + heartbeat</b>A separate thread refreshes <code>heartbeat_at</code> every 2 s while the job runs.</div>
          <div><b>Retry or DLQ</b>Failures back off exponentially with full jitter; a scheduler using <code>FOR UPDATE SKIP LOCKED</code> re-queues them when due.</div>
          <div><b>Recover</b>Stale heartbeats and orphaned processing lists are reclaimed, so a killed worker's jobs finish elsewhere.</div>
        </div>
        <div className="stack">
          {["Java 17", "Spring Boot 3.5", "PostgreSQL 15", "Redis 7", "React", "Prometheus", "Grafana", "Docker Compose", "GitHub Actions"].map((t) => <span key={t}>{t}</span>)}
        </div>
      </section>

      <footer>
        Demo data is cleared periodically. Write endpoints are rate-limited per visitor.
      </footer>
    </div>
  );
}
