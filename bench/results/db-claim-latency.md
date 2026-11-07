NOTICE:  database "jobqueue_bench" does not exist, skipping
seeding 1,000,000 rows...
psql:/bench/seed.sql:3: NOTICE:  table "jobs" does not exist, skipping

clients=8 duration=30s, rows=1,000,000 (30k due retries)

| variant | claims/s | avg ms | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|
| no index, FOR UPDATE | 32.562241 | 245.01 | 208.95 | 431.93 | 564.85 |
| no index, SKIP LOCKED | 42.099768 | 189.67 | 178.87 | 246.38 | 373.77 |
| composite index, FOR UPDATE | 617.642203 | 12.93 | 9.98 | 32.63 | 49.37 |
| composite index, SKIP LOCKED | 2503.685589 | 3.13 | 2.76 | 5.12 | 9.82 |

EXPLAIN (indexed, SKIP LOCKED):
                                                                     QUERY PLAN                                                                     
----------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.43..22.73 rows=10 width=22) (actual time=0.077..0.114 rows=10 loops=1)
   ->  LockRows  (cost=0.43..1998.79 rows=896 width=22) (actual time=0.076..0.112 rows=10 loops=1)
         ->  Index Scan using idx_jobs_status_next_retry on jobs  (cost=0.43..1989.83 rows=896 width=22) (actual time=0.053..0.080 rows=10 loops=1)
               Index Cond: (((status)::text = 'RETRYING'::text) AND (next_retry_at <= now()))
 Planning Time: 1.847 ms
 Execution Time: 0.173 ms
