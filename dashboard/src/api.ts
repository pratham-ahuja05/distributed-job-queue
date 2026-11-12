import type { Job, JobStatus, Priority, Snapshot } from "./types";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error((body as { error?: string }).error ?? `${res.status} ${res.statusText}`);
  }
  return body as T;
}

export const api = {
  stats: () => request<Snapshot>("/api/stats"),
  submit: (type: string, priority: Priority, maxRetries: number, payload?: Record<string, unknown>) =>
    request<Job>("/api/jobs", { method: "POST", body: JSON.stringify({ type, priority, maxRetries, payload }) }),
  burst: (count: number, durationMs: number) =>
    request<{ submitted: number }>("/api/jobs/batch", {
      method: "POST",
      body: JSON.stringify({ count, durationMs }),
    }),
  jobs: (status: JobStatus | "ALL", size = 12) =>
    request<{ items: Job[]; totalElements: number }>(
      `/api/jobs?size=${size}${status === "ALL" ? "" : `&status=${status}`}`,
    ),
  setFailureRate: (failureRate: number) =>
    request<{ failureRate: number }>("/api/settings", { method: "PUT", body: JSON.stringify({ failureRate }) }),
  crashWorker: () => request<{ instance: string }>("/api/chaos/crash-worker", { method: "POST" }),
  redrive: (id: number) => request<{ requeued: number }>(`/api/dlq/${id}/redrive`, { method: "POST" }),
  redriveAll: () => request<{ requeued: number }>("/api/dlq/redrive-all", { method: "POST" }),
};
