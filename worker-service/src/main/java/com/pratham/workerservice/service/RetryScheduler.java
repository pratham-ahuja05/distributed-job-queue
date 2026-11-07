package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore;
import com.pratham.workerservice.store.JobStore.Requeue;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Moves RETRYING jobs whose backoff has elapsed back onto their priority queue.
 * Runs in every worker container; FOR UPDATE SKIP LOCKED partitions the due rows between them.
 */
@Service
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final JobStore store;
    private final RedisJobQueue queue;
    private final WorkerProperties props;
    private final MeterRegistry meters;

    public RetryScheduler(JobStore store, RedisJobQueue queue, WorkerProperties props, MeterRegistry meters) {
        this.store = store;
        this.queue = queue;
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(fixedDelay = 500)
    public int releaseDue() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
                // Row is committed as QUEUED before its ID is pushed, so a worker can always claim it.
                List<Requeue> batch = store.releaseDueRetries(props.sweepBatchSize());
                for (Requeue r : batch) {
                    queue.enqueue(r.priority(), r.id());
                }
                total += batch.size();
                if (batch.size() < props.sweepBatchSize()) break;
            }
        } catch (RuntimeException e) {
            log.warn("Retry scheduler pass failed: {}", e.toString());
        }
        if (total > 0) meters.counter("jobs_retry_released_total").increment(total);
        return total;
    }
}
