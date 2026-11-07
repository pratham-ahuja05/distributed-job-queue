package com.pratham.workerservice.queue;

import java.util.List;

/**
 * Redis key names shared with api-service (kept identical in both modules).
 */
public final class QueueKeys {

    public static final String HIGH = "queue:high";
    public static final String MEDIUM = "queue:medium";
    public static final String LOW = "queue:low";
    public static final String DLQ = "queue:dead_letter";

    /** Queues indexed by priority level - 1 (1 = HIGH, 2 = MEDIUM, 3 = LOW). */
    public static final List<String> BY_PRIORITY = List.of(HIGH, MEDIUM, LOW);

    public static final String PROCESSING_PREFIX = "processing:";
    public static final String WORKER_PREFIX = "worker:";
    public static final String REAPING_PREFIX = "reaping:";

    public static final String EVENTS_CHANNEL = "events:jobs";
    public static final String CHAOS_CHANNEL = "chaos:crash";
    public static final String FAILURE_RATE_KEY = "settings:failure_rate";

    private QueueKeys() {
    }

    public static String queueFor(int priority) {
        if (priority < 1 || priority > 3) {
            throw new IllegalArgumentException("priority must be 1..3, got " + priority);
        }
        return BY_PRIORITY.get(priority - 1);
    }
}
