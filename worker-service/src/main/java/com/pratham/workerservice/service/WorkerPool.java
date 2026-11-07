package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs `worker.concurrency` long-lived threads, each looping JobProcessor.processOne().
 * Two containers x 4 threads = 8 jobs in flight concurrently.
 *
 * On SIGTERM (docker stop / rolling deploy) the pool stops taking new jobs and waits for
 * in-flight ones to finish. On SIGKILL nothing runs - that's what RecoveryService is for.
 */
@Component
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);
    private static final long SHUTDOWN_GRACE_SECONDS = 25;

    private final JobProcessor processor;
    private final RedisJobQueue queue;
    private final WorkerProperties props;
    private final List<WorkerSlot> slots = new ArrayList<>();
    private volatile boolean running;
    private ExecutorService executor;

    public WorkerPool(JobProcessor processor, RedisJobQueue queue, WorkerProperties props, MeterRegistry meters) {
        this.processor = processor;
        this.queue = queue;
        this.props = props;
        for (int i = 0; i < props.concurrency(); i++) {
            slots.add(new WorkerSlot(props.instanceId() + "-t" + i, props.instanceId()));
        }
        Gauge.builder("worker_threads_busy", processor, p -> p.inFlight().size()).register(meters);
        Gauge.builder("worker_threads_total", slots, List::size).register(meters);
    }

    public List<WorkerSlot> slots() {
        return slots;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        AtomicInteger n = new AtomicInteger();
        executor = Executors.newFixedThreadPool(slots.size(), r -> {
            Thread t = new Thread(r, "job-worker-" + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        });
        for (WorkerSlot slot : slots) {
            queue.register(slot.workerId(), slot.toFields(), Duration.ofSeconds(props.registryTtlSeconds()));
            executor.submit(() -> loop(slot));
        }
        log.info("Worker pool started: instance={} threads={}", props.instanceId(), slots.size());
    }

    void loop(WorkerSlot slot) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean didWork = processor.processOne(slot);
                if (!didWork) {
                    Thread.sleep(props.idleSleepMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Redis/Postgres blip: back off and keep the thread alive.
                log.warn("Worker {} loop error: {}", slot.workerId(), e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("In-flight jobs did not finish in {}s; interrupting", SHUTDOWN_GRACE_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (WorkerSlot slot : slots) {
            try {
                queue.unregister(slot.workerId());
            } catch (RuntimeException ignored) {
                // Redis may already be gone during shutdown; the key expires on its own.
            }
        }
        log.info("Worker pool stopped: instance={}", props.instanceId());
    }

    @Override
    public boolean isAutoStartup() {
        return props.autoStart();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after everything else, stop before the connection pools close. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
