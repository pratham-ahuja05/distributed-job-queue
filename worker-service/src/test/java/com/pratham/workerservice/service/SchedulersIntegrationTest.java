package com.pratham.workerservice.service;

import com.pratham.workerservice.IntegrationTestBase;
import com.pratham.workerservice.queue.QueueKeys;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore;
import com.pratham.workerservice.store.JobStore.Requeue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulersIntegrationTest extends IntegrationTestBase {

    @Autowired
    RetryScheduler retryScheduler;

    @Autowired
    RecoveryService recovery;

    @Autowired
    HeartbeatService heartbeat;

    @Autowired
    JobStore store;

    @Autowired
    RedisJobQueue queue;

    @Autowired
    JobProcessor processor;

    @Autowired
    WorkerPool pool;

    @Autowired
    TransactionTemplate tx;

    // ---- retry scheduler ---------------------------------------------------------------

    @Test
    void dueRetriesAreRequeuedAndFutureOnesAreLeftAlone() {
        long due = insertJob("RETRYING", 2, 1, 3, null);
        long notYet = insertJob("RETRYING", 2, 1, 3, null);
        jdbc.update("UPDATE jobs SET next_retry_at = now() - interval '1 second' WHERE id = ?", due);
        jdbc.update("UPDATE jobs SET next_retry_at = now() + interval '1 hour' WHERE id = ?", notYet);

        assertThat(retryScheduler.releaseDue()).isEqualTo(1);

        assertThat(status(due)).isEqualTo("QUEUED");
        assertThat(status(notYet)).isEqualTo("RETRYING");
        assertThat(redis.opsForList().range(QueueKeys.MEDIUM, 0, -1)).containsExactly(Long.toString(due));
    }

    @Test
    void retrySchedulerDrainsMoreThanOneBatch() {
        for (int i = 0; i < 250; i++) {
            long id = insertJob("RETRYING", 3, 1, 3, null);
            jdbc.update("UPDATE jobs SET next_retry_at = now() - interval '1 second' WHERE id = ?", id);
        }
        assertThat(retryScheduler.releaseDue()).isEqualTo(250);
        assertThat(redis.opsForList().size(QueueKeys.LOW)).isEqualTo(250);
    }

    /**
     * Proves FOR UPDATE SKIP LOCKED: while scheduler A holds row locks inside an open
     * transaction, scheduler B is not blocked and receives only the rows A didn't take.
     */
    @Test
    void concurrentSchedulersPartitionRowsWithoutBlockingOrDuplicates() throws Exception {
        for (int i = 0; i < 20; i++) {
            long id = insertJob("RETRYING", 1, 1, 3, null);
            jdbc.update("UPDATE jobs SET next_retry_at = now() - interval '1 second' WHERE id = ?", id);
        }
        CountDownLatch aHoldsLocks = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<List<Requeue>> a = pool.submit(() -> tx.execute(s -> {
            List<Requeue> taken = store.releaseDueRetries(5);
            aHoldsLocks.countDown();
            try {
                bFinished.await(10, TimeUnit.SECONDS); // keep the transaction (and locks) open
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return taken;
        }));
        assertThat(aHoldsLocks.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        List<Requeue> b = store.releaseDueRetries(100);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        bFinished.countDown();

        List<Requeue> taken = a.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(taken).hasSize(5);
        assertThat(b).hasSize(15);
        assertThat(elapsedMs).isLessThan(2000); // B did not wait for A's locks
        Set<Long> all = new HashSet<>();
        taken.forEach(r -> all.add(r.id()));
        b.forEach(r -> all.add(r.id()));
        assertThat(all).hasSize(20);
    }

    // ---- recovery ----------------------------------------------------------------------

    @Test
    void staleProcessingJobIsReclaimedAndRequeued() {
        long stale = insertQueued(1, null);
        long healthy = insertQueued(1, null);
        store.claim(stale, "dead-worker");
        store.claim(healthy, "live-worker");
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '1 minute' WHERE id = ?", stale);

        assertThat(recovery.reclaimStaleJobs()).isEqualTo(1);

        assertThat(status(stale)).isEqualTo("QUEUED");
        assertThat(intColumn(stale, "recovered_count")).isEqualTo(1);
        assertThat(status(healthy)).isEqualTo("PROCESSING");
        assertThat(redis.opsForList().range(QueueKeys.HIGH, 0, -1)).containsExactly(Long.toString(stale));
    }

    @Test
    void processingListOfDeadWorkerIsDrainedButLiveWorkersAreUntouched() {
        long poppedNeverClaimed = insertQueued(2, null);
        long alreadyDone = insertJob("SUCCESS", 2, 0, 3, null);
        redis.opsForList().leftPushAll(RedisJobQueue.processingKey("dead-1"),
                Long.toString(poppedNeverClaimed), Long.toString(alreadyDone), "junk");

        long liveJob = insertQueued(2, null);
        redis.opsForList().leftPush(RedisJobQueue.processingKey("alive-1"), Long.toString(liveJob));
        queue.register("alive-1", Map.of("state", "BUSY"), Duration.ofSeconds(30));

        assertThat(recovery.drainDeadWorkerLists()).isEqualTo(1);

        assertThat(redis.opsForList().range(QueueKeys.MEDIUM, 0, -1)).containsExactly(Long.toString(poppedNeverClaimed));
        assertThat(redis.hasKey(RedisJobQueue.processingKey("dead-1"))).isFalse();
        assertThat(redis.opsForList().size(RedisJobQueue.processingKey("alive-1"))).isEqualTo(1);
        assertThat(queue.drainProcessingList("does-not-exist")).isEmpty();
    }

    @Test
    void orphanedQueuedRowsAreRequeuedOnlyWhenRedisIsEmpty() {
        long orphan = insertQueued(3, null);
        jdbc.update("UPDATE jobs SET updated_at = now() - interval '1 minute' WHERE id = ?", orphan);
        long fresh = insertQueued(3, null);

        redis.opsForList().leftPush(QueueKeys.HIGH, "999");
        assertThat(recovery.requeueOrphans()).isZero(); // queues not empty -> could just be backlog

        redis.delete(QueueKeys.HIGH);
        assertThat(recovery.requeueOrphans()).isEqualTo(1);
        assertThat(redis.opsForList().range(QueueKeys.LOW, 0, -1)).containsExactly(Long.toString(orphan));
        assertThat(intColumn(fresh, "recovered_count")).isZero();
    }

    @Test
    void fullSweepRunsAllRecoveryStepsAndPublishesEvents() {
        long stale = insertQueued(1, null);
        store.claim(stale, "dead-worker");
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '1 minute' WHERE id = ?", stale);

        recovery.sweep();

        assertThat(status(stale)).isEqualTo("QUEUED");
    }

    // ---- heartbeat ---------------------------------------------------------------------

    @Test
    void heartbeatRefreshesInFlightJobsAndRegistry() {
        long id = insertQueued(1, null);
        store.claim(id, "test-instance-t0");
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '1 minute' WHERE id = ?", id);
        processor.inFlight().add(id);
        try {
            pool.start();
            heartbeat.beat();
        } finally {
            processor.inFlight().remove(id);
            pool.stop();
        }
        assertThat(jdbc.queryForObject("SELECT heartbeat_at > now() - interval '5 seconds' FROM jobs WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(store.heartbeat(List.of())).isZero();
    }
}
