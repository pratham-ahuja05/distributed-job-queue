package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Proves this container is alive, on a thread separate from the job threads so a long job
 * doesn't look like a dead worker:
 *  - Postgres: bumps heartbeat_at on every in-flight job (reaper reclaims rows that go stale)
 *  - Redis:    refreshes the TTL'd worker:{id} keys (reaper drains processing lists of expired ones)
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final JobProcessor processor;
    private final WorkerPool pool;
    private final JobStore store;
    private final RedisJobQueue queue;
    private final WorkerProperties props;

    public HeartbeatService(JobProcessor processor, WorkerPool pool, JobStore store,
                            RedisJobQueue queue, WorkerProperties props) {
        this.processor = processor;
        this.pool = pool;
        this.store = store;
        this.queue = queue;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${worker.heartbeat-interval-ms:2000}")
    public void beat() {
        if (!pool.isRunning()) return;
        try {
            store.heartbeat(List.copyOf(processor.inFlight()));
            Duration ttl = Duration.ofSeconds(props.registryTtlSeconds());
            for (WorkerSlot slot : pool.slots()) {
                queue.register(slot.workerId(), slot.toFields(), ttl);
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.toString());
        }
    }
}
