package com.pratham.workerservice.service;

import com.pratham.workerservice.IntegrationTestBase;
import com.pratham.workerservice.queue.QueueKeys;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobProcessorIntegrationTest extends IntegrationTestBase {

    @Autowired
    JobProcessor processor;

    @Autowired
    JobStore store;

    @Autowired
    RedisJobQueue queue;

    private final WorkerSlot slot = new WorkerSlot("test-instance-t0", "test-instance");

    private String processingKey() {
        return RedisJobQueue.processingKey(slot.workerId());
    }

    @Test
    void returnsFalseWhenEveryQueueIsEmpty() throws Exception {
        assertThat(processor.processOne(slot)).isFalse();
    }

    @Test
    void successfulJobIsMarkedSuccessAndAcked() throws Exception {
        long id = insertQueued(1, "{\"durationMs\":0,\"failRate\":0}");
        queue.enqueue(1, id);

        assertThat(processor.processOne(slot)).isTrue();

        assertThat(status(id)).isEqualTo("SUCCESS");
        assertThat(intColumn(id, "attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM jobs WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(redis.opsForList().size(processingKey())).isZero();
        assertThat(slot.processed()).isEqualTo(1);
        assertThat(processor.inFlight()).isEmpty();
        assertThat(redis.opsForHash().get("worker:" + slot.workerId(), "state")).isEqualTo("IDLE");
    }

    @Test
    void failedJobWithRetriesLeftIsScheduledForRetryWithBackoff() throws Exception {
        long id = insertQueued(2, "{\"durationMs\":0,\"failRate\":1.0}");
        queue.enqueue(2, id);

        processor.processOne(slot);

        assertThat(status(id)).isEqualTo("RETRYING");
        assertThat(intColumn(id, "retry_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_retry_at >= updated_at FROM jobs WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error FROM jobs WHERE id = ?", String.class, id)).contains("simulated");
        assertThat(jdbc.queryForObject("SELECT worker_id FROM jobs WHERE id = ?", String.class, id)).isNull();
        assertThat(redis.opsForList().size(processingKey())).isZero();
        assertThat(slot.failed()).isEqualTo(1);
    }

    @Test
    void failedJobWithoutRetriesLeftGoesToDeadLetterQueue() throws Exception {
        long id = insertJob("QUEUED", 3, 3, 3, "{\"durationMs\":0,\"failRate\":1.0}");
        queue.enqueue(3, id);

        processor.processOne(slot);

        assertThat(status(id)).isEqualTo("DLQ");
        assertThat(redis.opsForList().range(QueueKeys.DLQ, 0, -1)).containsExactly(Long.toString(id));
    }

    @Test
    void globalFailureRateFromRedisAppliesWhenPayloadHasNone() throws Exception {
        redis.opsForValue().set(QueueKeys.FAILURE_RATE_KEY, "1.0");
        long id = insertQueued(1, null);
        queue.enqueue(1, id);

        processor.processOne(slot);

        assertThat(status(id)).isEqualTo("RETRYING");
    }

    @Test
    void duplicateIdInQueueIsClaimedOnlyOnce() throws Exception {
        long id = insertQueued(1, "{\"durationMs\":0}");
        queue.enqueue(1, id);
        queue.enqueue(1, id);

        processor.processOne(slot);
        processor.processOne(slot);

        assertThat(intColumn(id, "attempts")).isEqualTo(1);
        assertThat(redis.opsForList().size(QueueKeys.HIGH)).isZero();
        assertThat(redis.opsForList().size(processingKey())).isZero();
    }

    @Test
    void malformedQueueEntryIsDropped() throws Exception {
        redis.opsForList().leftPush(QueueKeys.HIGH, "not-a-number");

        assertThat(processor.processOne(slot)).isTrue();
        assertThat(redis.opsForList().size(processingKey())).isZero();
    }

    @Test
    void workerThatLostOwnershipCannotOverwriteTheResult() {
        long id = insertQueued(1, null);
        assertThat(store.claim(id, "worker-a")).isPresent();
        // Reaper hands the job to someone else while worker-a is presumed dead.
        jdbc.update("UPDATE jobs SET worker_id = 'worker-b' WHERE id = ?", id);

        assertThat(store.markSuccess(id, "worker-a")).isEmpty();
        assertThat(store.markRetry(id, "worker-a", 10, "x")).isFalse();
        assertThat(store.markDead(id, "worker-a", "x")).isFalse();
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(store.markSuccess(id, "worker-b")).isPresent();
    }

    @Test
    void claimRefusesJobsThatAreNotQueued() {
        long retrying = insertJob("RETRYING", 1, 1, 3, null);
        long done = insertJob("SUCCESS", 1, 0, 3, null);
        assertThat(store.claim(retrying, "w")).isEmpty();
        assertThat(store.claim(done, "w")).isEmpty();
        assertThat(store.claim(9999, "w")).isEmpty();
    }

    @Test
    void longErrorMessagesAreTruncated() {
        long id = insertQueued(1, null);
        store.claim(id, "w");
        store.markDead(id, "w", "e".repeat(5000));
        assertThat(jdbc.queryForObject("SELECT length(last_error) FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(1000);
    }
}
