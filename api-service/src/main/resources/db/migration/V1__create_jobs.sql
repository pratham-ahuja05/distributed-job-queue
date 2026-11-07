-- Single source of truth for job state. Redis only carries job IDs for dispatch;
-- every state transition is a conditional UPDATE against this table.
CREATE TABLE jobs (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(64)  NOT NULL,
    payload         TEXT,
    status          VARCHAR(16)  NOT NULL,
    priority        INTEGER      NOT NULL CHECK (priority BETWEEN 1 AND 3),
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    max_retries     INTEGER      NOT NULL DEFAULT 3,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    recovered_count INTEGER      NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    worker_id       VARCHAR(128),
    heartbeat_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

-- Composite indexes, one per access path. Each scheduler query filters on
-- status first and then range-scans / orders on the second column.

-- Retry scheduler: WHERE status = 'RETRYING' AND next_retry_at <= now() ORDER BY next_retry_at
CREATE INDEX idx_jobs_status_next_retry   ON jobs (status, next_retry_at);

-- Recovery reaper: WHERE status = 'PROCESSING' AND heartbeat_at < now() - interval
CREATE INDEX idx_jobs_status_heartbeat    ON jobs (status, heartbeat_at);

-- Orphan sweep / backlog listing: WHERE status = 'QUEUED' ORDER BY priority, created_at
CREATE INDEX idx_jobs_status_priority_created ON jobs (status, priority, created_at);

-- Per-run benchmark statistics and dashboard filters: WHERE type = ? (AND status = ?)
CREATE INDEX idx_jobs_type_status         ON jobs (type, status);
