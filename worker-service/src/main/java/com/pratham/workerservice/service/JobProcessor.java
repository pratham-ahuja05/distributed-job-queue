package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.queue.RedisJobQueue.Outcome;
import com.pratham.workerservice.queue.WeightedPriorityScheduler;
import com.pratham.workerservice.store.JobStore;
import com.pratham.workerservice.store.JobStore.ClaimedJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One iteration of the worker loop: pop -> claim -> execute -> record outcome -> ack.
 *
 * Ordering matters for at-least-once delivery: the ID is only removed from the worker's
 * Redis processing list (ack) after the outcome is committed in Postgres. A crash at any
 * point leaves either a processing-list entry or a stale heartbeat, both of which
 * RecoveryService turns back into a queued job.
 */
@Component
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final RedisJobQueue queue;
    private final WeightedPriorityScheduler scheduler;
    private final JobStore store;
    private final JobExecutor executor;
    private final BackoffPolicy backoff;
    private final MeterRegistry meters;
    private final WorkerProperties props;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public JobProcessor(RedisJobQueue queue, WeightedPriorityScheduler scheduler, JobStore store,
                        JobExecutor executor, BackoffPolicy backoff, MeterRegistry meters, WorkerProperties props) {
        this.queue = queue;
        this.scheduler = scheduler;
        this.store = store;
        this.executor = executor;
        this.backoff = backoff;
        this.meters = meters;
        this.props = props;
    }

    /** IDs currently being executed in this JVM (heart-beaten by HeartbeatService). */
    public Set<Long> inFlight() {
        return inFlight;
    }

    /** @return true if a queue entry was consumed (caller should poll again immediately). */
    public boolean processOne(WorkerSlot slot) throws InterruptedException {
        String workerId = slot.workerId();
        String raw = queue.pop(workerId, scheduler.nextOrder());
        if (raw == null) {
            return false;
        }

        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("Dropping malformed queue entry '{}'", raw);
            queue.ack(workerId, raw);
            return true;
        }

        Optional<ClaimedJob> claimed = store.claim(id, workerId);
        if (claimed.isEmpty()) {
            // Duplicate or stale ID (already claimed, finished, or waiting for a retry slot).
            meters.counter("jobs_claim_skipped_total").increment();
            queue.ack(workerId, raw);
            return true;
        }

        ClaimedJob job = claimed.get();
        inFlight.add(id);
        slot.busy(id, job.priority());
        queue.register(workerId, slot.toFields(), Duration.ofSeconds(props.registryTtlSeconds()));
        recordQueueWait(job);

        Outcome result = null;
        Timer.Sample sample = Timer.start(meters);
        try {
            executor.execute(job);
            result = onSuccess(job, workerId);
        } catch (InterruptedException e) {
            result = onFailure(job, workerId, "interrupted during shutdown");
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            result = onFailure(job, workerId, e.getMessage());
        } finally {
            boolean succeeded = result != null && "SUCCESS".equals(result.eventType());
            sample.stop(Timer.builder("job_execution")
                    .description("Time spent executing a job")
                    .tag("priority", priorityName(job.priority()))
                    .tag("outcome", succeeded ? "success" : "failure")
                    .publishPercentileHistogram()
                    .register(meters));
            inFlight.remove(id);
            slot.idle(succeeded);
            // Outcome is committed in Postgres; ack + registry + event (+ DLQ push) in one round trip.
            queue.complete(workerId, raw, slot.toFields(), Duration.ofSeconds(props.registryTtlSeconds()), result);
        }
        return true;
    }

    private Outcome onSuccess(ClaimedJob job, String workerId) {
        Optional<Long> latency = store.markSuccess(job.id(), workerId);
        if (latency.isEmpty()) {
            fenced(job, workerId);
            return null;
        }
        outcome(job, "success");
        Timer.builder("job_end_to_end")
                .description("Submit-to-completion latency of successful jobs")
                .tag("priority", priorityName(job.priority()))
                .publishPercentileHistogram()
                .register(meters)
                .record(Duration.ofMillis(latency.get()));
        return new Outcome("SUCCESS", Map.of("jobId", job.id(), "priority", job.priority(), "workerId", workerId,
                "attempt", job.attempts(), "latencyMs", latency.get()), null);
    }

    private Outcome onFailure(ClaimedJob job, String workerId, String error) {
        String message = error == null ? "unknown error" : error;
        if (job.retryCount() < job.maxRetries()) {
            int retryNumber = job.retryCount() + 1;
            long delay = backoff.delayFor(retryNumber);
            if (!store.markRetry(job.id(), workerId, delay, message)) {
                fenced(job, workerId);
                return null;
            }
            outcome(job, "retry");
            return new Outcome("RETRY", Map.of("jobId", job.id(), "priority", job.priority(), "workerId", workerId,
                    "attempt", job.attempts(), "retry", retryNumber, "delayMs", delay, "error", message), null);
        }
        if (!store.markDead(job.id(), workerId, message)) {
            fenced(job, workerId);
            return null;
        }
        outcome(job, "dlq");
        return new Outcome("DLQ", Map.of("jobId", job.id(), "priority", job.priority(), "workerId", workerId,
                "attempt", job.attempts(), "error", message), job.id());
    }

    private void fenced(ClaimedJob job, String workerId) {
        // The reaper reclaimed this job (we were presumed dead); the new owner's result wins.
        log.warn("Job {} no longer owned by {}; discarding result", job.id(), workerId);
        meters.counter("jobs_fenced_total").increment();
    }

    private void outcome(ClaimedJob job, String outcome) {
        meters.counter("jobs_processed_total", "outcome", outcome, "priority", priorityName(job.priority())).increment();
    }

    private void recordQueueWait(ClaimedJob job) {
        if (job.createdAt() == null) return;
        Duration wait = Duration.between(job.createdAt(), OffsetDateTime.now());
        Timer.builder("job_queue_wait")
                .description("Time from submission until a worker claimed the job")
                .tag("priority", priorityName(job.priority()))
                .publishPercentileHistogram()
                .register(meters)
                .record(wait.isNegative() ? Duration.ZERO : wait);
    }

    static String priorityName(int p) {
        return switch (p) {
            case 1 -> "HIGH";
            case 2 -> "MEDIUM";
            default -> "LOW";
        };
    }
}
