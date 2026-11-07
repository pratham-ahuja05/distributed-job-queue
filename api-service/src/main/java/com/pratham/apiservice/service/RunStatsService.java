package com.pratham.apiservice.service;

import com.pratham.apiservice.web.dto.Dtos.RunStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Aggregates computed in Postgres for one benchmark run (all jobs with the same `type`).
 * The load-test scripts read these numbers; nothing here is estimated client-side.
 */
@Service
public class RunStatsService {

    private static final String SQL = """
            SELECT count(*)                                              AS total,
                   count(*) FILTER (WHERE status = 'SUCCESS')            AS success,
                   count(*) FILTER (WHERE status = 'DLQ')                AS dlq,
                   count(*) FILTER (WHERE status NOT IN ('SUCCESS','DLQ')) AS pending,
                   percentile_cont(0.50) WITHIN GROUP (ORDER BY e2e_ms) FILTER (WHERE status = 'SUCCESS')::float8 AS p50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY e2e_ms) FILTER (WHERE status = 'SUCCESS')::float8 AS p95,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY e2e_ms) FILTER (WHERE status = 'SUCCESS')::float8 AS p99,
                   percentile_cont(0.50) WITHIN GROUP (ORDER BY wait_ms)::float8 AS w50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_ms)::float8 AS w95,
                   coalesce(avg(attempts), 0)::float8                          AS avg_attempts,
                   coalesce(sum(greatest(attempts - 1 - retry_count, 0)), 0) AS re_executions,
                   coalesce(sum(recovered_count), 0)                     AS recovered,
                   extract(epoch FROM max(completed_at) - min(created_at))::float8 AS span_s
            FROM (SELECT *,
                         (extract(epoch FROM completed_at - created_at) * 1000)::float8 AS e2e_ms,
                         (extract(epoch FROM started_at - created_at) * 1000)::float8 AS wait_ms
                  FROM jobs WHERE type = ?) j
            """;

    private final JdbcTemplate jdbc;

    public RunStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunStats forType(String type) {
        return jdbc.queryForObject(SQL, (rs, i) -> {
            long success = rs.getLong("success");
            Double span = (Double) rs.getObject("span_s", Double.class);
            Double throughput = span == null || span <= 0 ? null : success / span;
            return new RunStats(type, rs.getLong("total"), success, rs.getLong("dlq"), rs.getLong("pending"),
                    rs.getObject("p50", Double.class), rs.getObject("p95", Double.class), rs.getObject("p99", Double.class),
                    rs.getObject("w50", Double.class), rs.getObject("w95", Double.class),
                    rs.getDouble("avg_attempts"), rs.getLong("re_executions"), rs.getLong("recovered"),
                    span, throughput);
        }, type);
    }
}
