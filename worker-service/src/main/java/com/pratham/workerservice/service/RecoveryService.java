package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore;
import com.pratham.workerservice.store.JobStore.JobState;
import com.pratham.workerservice.store.JobStore.Requeue;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guarantees no job is lost when a worker dies mid-flight. Three independent sweeps:
 *
 * 1. Stale heartbeat  - PROCESSING rows whose worker stopped heart-beating -> QUEUED.
 * 2. Dead worker list - processing:{id} lists whose worker:{id} key expired; any IDs still
 *                       QUEUED in Postgres (popped but never claimed) are pushed back.
 * 3. Orphans          - QUEUED rows that are in no Redis list at all (API crashed between
 *                       commit and push, or Redis restarted). Only runs when Redis is empty.
 *
 * Every sweep is safe to run concurrently from all containers (SKIP LOCKED / atomic RENAME),
 * and a duplicate ID in a queue is harmless because claim() is conditional.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final JobStore store;
    private final RedisJobQueue queue;
    private final WorkerPool pool;
    private final WorkerProperties props;
    private final MeterRegistry meters;

    public RecoveryService(JobStore store, RedisJobQueue queue, WorkerPool pool,
                           WorkerProperties props, MeterRegistry meters) {
        this.store = store;
        this.queue = queue;
        this.pool = pool;
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void sweep() {
        try {
            reclaimStaleJobs();
            drainDeadWorkerLists();
            requeueOrphans();
        } catch (RuntimeException e) {
            log.warn("Recovery sweep failed: {}", e.toString());
        }
    }

    int reclaimStaleJobs() {
        List<Requeue> stale = store.reclaimStale(props.staleAfterSeconds(), props.sweepBatchSize());
        requeue(stale, "stale_heartbeat");
        return stale.size();
    }

    int drainDeadWorkerLists() {
        List<String> mine = pool.slots().stream().map(WorkerSlot::workerId).toList();
        int recovered = 0;
        for (String owner : queue.processingListOwners()) {
            if (mine.contains(owner) || queue.isAlive(owner)) continue;
            List<String> rawIds = queue.drainProcessingList(owner);
            if (rawIds.isEmpty()) continue;
            List<Long> ids = new ArrayList<>();
            for (String raw : rawIds) {
                try {
                    ids.add(Long.parseLong(raw));
                } catch (NumberFormatException ignored) {
                    // malformed entry: dropping it is the right thing
                }
            }
            Map<Long, JobState> states = store.states(ids);
            List<Requeue> toPush = new ArrayList<>();
            for (Long id : ids) {
                JobState s = states.get(id);
                // PROCESSING rows are handled by the heartbeat sweep; finished/retrying ones need nothing.
                if (s != null && "QUEUED".equals(s.status())) toPush.add(new Requeue(id, s.priority()));
            }
            log.info("Drained processing list of dead worker {}: {} entries, {} re-queued", owner, ids.size(), toPush.size());
            requeue(toPush, "dead_worker_list");
            recovered += toPush.size();
        }
        return recovered;
    }

    int requeueOrphans() {
        if (queue.totalQueued() > 0) return 0;
        List<Requeue> orphans = store.sweepOrphans(props.orphanAfterSeconds(), props.sweepBatchSize());
        requeue(orphans, "orphan");
        return orphans.size();
    }

    private void requeue(List<Requeue> jobs, String reason) {
        for (Requeue r : jobs) {
            queue.enqueue(r.priority(), r.id());
            queue.publish("RECOVERED", Map.of("jobId", r.id(), "priority", r.priority(), "reason", reason));
        }
        if (!jobs.isEmpty()) {
            meters.counter("jobs_recovered_total", "reason", reason).increment(jobs.size());
            log.info("Recovered {} job(s), reason={}", jobs.size(), reason);
        }
    }
}
