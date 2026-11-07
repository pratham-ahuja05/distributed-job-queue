package com.pratham.workerservice.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable state of one worker thread, mirrored to Redis for the dashboard. */
public class WorkerSlot {

    private final String workerId;
    private final String instanceId;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Long currentJobId;
    private volatile int currentPriority;
    private volatile long since = System.currentTimeMillis();

    public WorkerSlot(String workerId, String instanceId) {
        this.workerId = workerId;
        this.instanceId = instanceId;
    }

    public String workerId() {
        return workerId;
    }

    public Long currentJobId() {
        return currentJobId;
    }

    public void busy(long jobId, int priority) {
        currentJobId = jobId;
        currentPriority = priority;
        since = System.currentTimeMillis();
    }

    public void idle(boolean succeeded) {
        currentJobId = null;
        since = System.currentTimeMillis();
        if (succeeded) processed.incrementAndGet();
        else failed.incrementAndGet();
    }

    public long processed() {
        return processed.get();
    }

    public long failed() {
        return failed.get();
    }

    public Map<String, String> toFields() {
        Map<String, String> f = new LinkedHashMap<>();
        Long job = currentJobId;
        f.put("instance", instanceId);
        f.put("state", job == null ? "IDLE" : "BUSY");
        f.put("jobId", job == null ? "" : job.toString());
        f.put("priority", job == null ? "" : Integer.toString(currentPriority));
        f.put("since", Long.toString(since));
        f.put("processed", Long.toString(processed.get()));
        f.put("failed", Long.toString(failed.get()));
        f.put("heartbeat", Long.toString(System.currentTimeMillis()));
        return f;
    }
}
