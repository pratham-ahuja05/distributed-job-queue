export type JobStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "SUCCESS" | "DLQ";
export type Priority = "HIGH" | "MEDIUM" | "LOW";

export interface SeriesPoint {
  t: number;
  submitted: number;
  success: number;
  retry: number;
  dlq: number;
  recovered: number;
}

export interface WorkerInfo {
  id: string;
  instance: string;
  state: "BUSY" | "IDLE";
  jobId: string;
  priority: string;
  since: string;
  processed: string;
  failed: string;
  heartbeat: string;
}

export interface JobEvent {
  type: "SUBMITTED" | "SUCCESS" | "RETRY" | "DLQ" | "RECOVERED" | "REDRIVEN" | "CRASH_REQUESTED" | string;
  ts: number;
  jobId?: number;
  priority?: number;
  workerId?: string;
  attempt?: number;
  latencyMs?: number;
  delayMs?: number;
  retry?: number;
  error?: string;
  reason?: string;
  count?: number;
  instance?: string;
}

export interface Snapshot {
  ts: number;
  queues: Record<"HIGH" | "MEDIUM" | "LOW" | "DLQ", number>;
  statuses: Record<JobStatus, number>;
  workers: WorkerInfo[];
  series: SeriesPoint[];
  throughputPerSec: number;
  latencyMs: { p50: number | null; p95: number | null; p99: number | null; samples: number };
  failureRate: number;
  events: JobEvent[];
}

export interface Job {
  id: number;
  type: string;
  status: JobStatus;
  priority: Priority;
  retryCount: number;
  maxRetries: number;
  attempts: number;
  recoveredCount: number;
  workerId: string | null;
  lastError: string | null;
  createdAt: string;
  completedAt: string | null;
  nextRetryAt: string | null;
}
