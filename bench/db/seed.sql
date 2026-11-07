-- 1M-row jobs table shaped like a busy production queue:
-- 94% SUCCESS, 3% RETRYING (due), 2% PROCESSING, 1% DLQ.
DROP TABLE IF EXISTS jobs;
CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY, type VARCHAR(64) NOT NULL, payload TEXT, status VARCHAR(16) NOT NULL,
    priority INTEGER NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, max_retries INTEGER NOT NULL DEFAULT 3,
    attempts INTEGER NOT NULL DEFAULT 0, recovered_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ, worker_id VARCHAR(128), heartbeat_at TIMESTAMPTZ, last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ);

INSERT INTO jobs (type, payload, status, priority, next_retry_at, heartbeat_at, created_at)
SELECT 'bench', '{"to":"user@example.com"}',
       CASE WHEN r < 0.94 THEN 'SUCCESS' WHEN r < 0.97 THEN 'RETRYING' WHEN r < 0.99 THEN 'PROCESSING' ELSE 'DLQ' END,
       1 + (g % 3),
       CASE WHEN r >= 0.94 AND r < 0.97 THEN now() - (random() * interval '10 minutes') END,
       CASE WHEN r >= 0.97 AND r < 0.99 THEN now() - (random() * interval '10 seconds') END,
       now() - (random() * interval '7 days')
FROM (SELECT g, random() AS r FROM generate_series(1, 1000000) g) s;
VACUUM ANALYZE jobs;
