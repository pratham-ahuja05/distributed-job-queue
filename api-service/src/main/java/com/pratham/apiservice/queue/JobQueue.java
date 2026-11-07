package com.pratham.apiservice.queue;

import com.pratham.apiservice.domain.Priority;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Thin wrapper over the Redis lists the workers consume. Only job IDs go into Redis;
 * callers must commit the database row before enqueueing, otherwise a fast worker
 * could pop an ID whose row is not yet visible.
 */
@Component
public class JobQueue {

    private final StringRedisTemplate redis;

    public JobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(Priority priority, long jobId) {
        redis.opsForList().leftPush(priority.queue(), Long.toString(jobId));
    }

    /** Enqueue many jobs with one LPUSH per priority list. */
    public void enqueueAll(Map<Priority, List<Long>> idsByPriority) {
        idsByPriority.forEach((priority, ids) -> {
            if (!ids.isEmpty()) {
                redis.opsForList().leftPushAll(priority.queue(),
                        ids.stream().map(String::valueOf).toList());
            }
        });
    }

    public Map<String, Long> depths() {
        Map<String, Long> depths = new LinkedHashMap<>();
        depths.put("HIGH", size(QueueKeys.HIGH));
        depths.put("MEDIUM", size(QueueKeys.MEDIUM));
        depths.put("LOW", size(QueueKeys.LOW));
        depths.put("DLQ", size(QueueKeys.DLQ));
        return depths;
    }

    public long pendingTotal() {
        return size(QueueKeys.HIGH) + size(QueueKeys.MEDIUM) + size(QueueKeys.LOW);
    }

    public void removeFromDlq(long jobId) {
        redis.opsForList().remove(QueueKeys.DLQ, 0, Long.toString(jobId));
    }

    private long size(String key) {
        Long size = redis.opsForList().size(key);
        return size == null ? 0 : size;
    }
}
