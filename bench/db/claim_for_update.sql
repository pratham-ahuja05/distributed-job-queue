WITH due AS (SELECT id FROM jobs WHERE status = 'RETRYING' AND next_retry_at <= now()
             ORDER BY next_retry_at LIMIT 10 FOR UPDATE)
UPDATE jobs j SET updated_at = now() FROM due WHERE j.id = due.id;
