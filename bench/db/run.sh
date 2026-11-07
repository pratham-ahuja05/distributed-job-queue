#!/bin/sh
# Runs inside the postgres container:  docker compose exec -T postgres sh /bench/run.sh
# Compares the retry-scheduler claim query (production SQL, 10 rows per claim) under
# 8 concurrent pollers: {no index, composite index} x {FOR UPDATE, FOR UPDATE SKIP LOCKED}.
# Rows keep status RETRYING (only updated_at changes) so every run sees the same data.
set -e
DB=jobqueue_bench
DURATION=${DURATION:-30}
CLIENTS=${CLIENTS:-8}
psql -U postgres -qc "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
echo "seeding 1,000,000 rows..."
psql -U postgres -d $DB -q -f /bench/seed.sql

run() { # label script
  cd /tmp && rm -f pgbench_log.*
  psql -U postgres -d $DB -qc "VACUUM ANALYZE jobs" >/dev/null
  pgbench -U postgres -d $DB -n -c $CLIENTS -j $CLIENTS -T $DURATION -f /bench/$2 -l >/tmp/out.txt 2>&1
  tps=$(grep -E "^tps" /tmp/out.txt | head -1 | sed -E 's/tps = ([0-9.]+).*/\1/')
  # log field 3 = transaction latency in microseconds
  cat pgbench_log.* | awk '{print $3}' | sort -n > /tmp/lat.txt
  n=$(wc -l < /tmp/lat.txt)
  p50=$(awk -v n=$n 'NR==int(n*0.50)+1{printf "%.2f", $1/1000}' /tmp/lat.txt)
  p95=$(awk -v n=$n 'NR==int(n*0.95)+1{printf "%.2f", $1/1000}' /tmp/lat.txt)
  p99=$(awk -v n=$n 'NR==int(n*0.99)+1{printf "%.2f", $1/1000}' /tmp/lat.txt)
  avg=$(awk '{s+=$1} END{printf "%.2f", s/NR/1000}' /tmp/lat.txt)
  echo "| $1 | $tps | $avg | $p50 | $p95 | $p99 |"
}

echo
echo "clients=$CLIENTS duration=${DURATION}s, rows=1,000,000 (30k due retries)"
echo
echo "| variant | claims/s | avg ms | p50 ms | p95 ms | p99 ms |"
echo "|---|---|---|---|---|---|"
run "no index, FOR UPDATE" claim_for_update.sql
run "no index, SKIP LOCKED" claim_skip_locked.sql
psql -U postgres -d $DB -q -c "CREATE INDEX idx_jobs_status_next_retry ON jobs (status, next_retry_at)" \
     -c "CREATE INDEX idx_jobs_status_heartbeat ON jobs (status, heartbeat_at)" \
     -c "CREATE INDEX idx_jobs_status_priority_created ON jobs (status, priority, created_at)"
run "composite index, FOR UPDATE" claim_for_update.sql
run "composite index, SKIP LOCKED" claim_skip_locked.sql
echo
echo "EXPLAIN (indexed, SKIP LOCKED):"
psql -U postgres -d $DB -c "EXPLAIN ANALYZE SELECT id FROM jobs WHERE status='RETRYING' AND next_retry_at <= now() ORDER BY next_retry_at LIMIT 10 FOR UPDATE SKIP LOCKED" | sed -n '1,8p'
psql -U postgres -qc "DROP DATABASE $DB"
