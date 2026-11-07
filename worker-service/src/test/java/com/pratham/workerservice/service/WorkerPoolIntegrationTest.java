package com.pratham.workerservice.service;

import com.pratham.workerservice.IntegrationTestBase;
import com.pratham.workerservice.queue.RedisJobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end: the real thread pool drains a mixed workload with concurrent threads. */
class WorkerPoolIntegrationTest extends IntegrationTestBase {

    @Autowired
    WorkerPool pool;

    @Autowired
    RedisJobQueue queue;

    @Autowired
    RetryScheduler retryScheduler;

    @Test
    void poolProcessesEveryJobExactlyOnceAcrossConcurrentThreads() throws Exception {
        int jobs = 300;
        for (int i = 0; i < jobs; i++) {
            int priority = 1 + (i % 3);
            long id = insertQueued(priority, "{\"durationMs\":5,\"failRate\":0}");
            queue.enqueue(priority, id);
        }

        pool.start();
        try {
            assertThat(pool.isRunning()).isTrue();
            assertThat(pool.slots()).hasSize(4);
            waitUntil(() -> count("SUCCESS") == jobs, 60_000);
        } finally {
            pool.stop();
        }

        // Each job ran once, and work was spread across all four threads.
        assertThat(jdbc.queryForObject("SELECT max(attempts) FROM jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT worker_id) FROM jobs", Integer.class)).isEqualTo(4);
        assertThat(pool.isRunning()).isFalse();
        assertThat(redis.keys("worker:*")).isEmpty(); // graceful stop unregisters
        pool.stop(); // idempotent
    }

    @Test
    void failingJobsEndUpRetriedThenDeadLettered() throws Exception {
        long id = insertJob("QUEUED", 1, 0, 2, "{\"durationMs\":0,\"failRate\":1.0}");
        queue.enqueue(1, id);

        pool.start();
        try {
            waitUntil(() -> {
                retryScheduler.releaseDue();
                return "DLQ".equals(status(id));
            }, 30_000);
        } finally {
            pool.stop();
        }
        assertThat(intColumn(id, "attempts")).isEqualTo(3);  // first try + 2 retries
        assertThat(intColumn(id, "retry_count")).isEqualTo(2);
    }

    @Test
    void loopSurvivesInfrastructureErrors() throws Exception {
        // A non-numeric registry value can't break anything, but a WRONGTYPE error on the
        // processing key makes every pop throw; the loop must log, back off and keep going.
        redis.opsForValue().set(RedisJobQueue.processingKey("test-instance-t0"), "not-a-list");
        long id = insertQueued(1, "{\"durationMs\":0}");
        queue.enqueue(1, id);

        pool.start();
        try {
            waitUntil(() -> "SUCCESS".equals(status(id)), 15_000);
        } finally {
            pool.stop();
        }
        assertThat(Map.of("status", status(id))).containsEntry("status", "SUCCESS");
    }

    private long count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = ?", Long.class, status);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(50);
        }
    }
}
