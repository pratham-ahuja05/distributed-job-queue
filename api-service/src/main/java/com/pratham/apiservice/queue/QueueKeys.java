package com.pratham.apiservice.queue;

/**
 * Redis key names shared with worker-service (kept identical in both modules).
 */
public final class QueueKeys {

    public static final String HIGH = "queue:high";
    public static final String MEDIUM = "queue:medium";
    public static final String LOW = "queue:low";
    public static final String DLQ = "queue:dead_letter";

    /** Per worker-thread in-flight list: processing:{workerId}. */
    public static final String PROCESSING_PREFIX = "processing:";
    /** Per worker-thread liveness hash with TTL: worker:{workerId}. */
    public static final String WORKER_PREFIX = "worker:";

    /** Pub/sub channel carrying job lifecycle events (JSON). */
    public static final String EVENTS_CHANNEL = "events:jobs";
    /** Pub/sub channel used by the demo's "crash a worker" button. */
    public static final String CHAOS_CHANNEL = "chaos:crash";

    /** Runtime-tunable simulated failure rate (0..1) read by workers. */
    public static final String FAILURE_RATE_KEY = "settings:failure_rate";

    private QueueKeys() {
    }
}
