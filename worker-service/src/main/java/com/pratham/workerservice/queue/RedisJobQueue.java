package com.pratham.workerservice.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * All Redis access for the worker. Redis only carries job IDs; Postgres holds state.
 *
 * Reliable-queue pattern: a pop atomically moves the ID from a priority list into this
 * worker thread's own processing:{workerId} list, so an ID is never "in the air". If the
 * worker dies, the ID is still in its processing list and RecoveryService puts it back.
 */
@Component
public class RedisJobQueue {

    /**
     * KEYS[1..n-1] = priority queues in the order to try, KEYS[n] = this worker's processing list.
     * Runs atomically inside Redis, so trying several queues costs one round trip and can't race.
     */
    private static final DefaultRedisScript<String> POP_SCRIPT = new DefaultRedisScript<>("""
            for i = 1, #KEYS - 1 do
              local id = redis.call('RPOPLPUSH', KEYS[i], KEYS[#KEYS])
              if id then return id end
            end
            return false
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisJobQueue(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public static String processingKey(String workerId) {
        return QueueKeys.PROCESSING_PREFIX + workerId;
    }

    public static String registryKey(String workerId) {
        return QueueKeys.WORKER_PREFIX + workerId;
    }

    /** Pops the next job ID, trying queues in the given order. Returns null if all are empty. */
    public String pop(String workerId, List<String> queueOrder) {
        List<String> keys = new ArrayList<>(queueOrder);
        keys.add(processingKey(workerId));
        return redis.execute(POP_SCRIPT, keys);
    }

    /** Removes the ID from the worker's processing list once its outcome is durable in Postgres. */
    public void ack(String workerId, String jobId) {
        redis.opsForList().remove(processingKey(workerId), 1, jobId);
    }

    public void enqueue(int priority, long jobId) {
        redis.opsForList().leftPush(QueueKeys.queueFor(priority), Long.toString(jobId));
    }

    public long totalQueued() {
        long total = 0;
        for (String q : QueueKeys.BY_PRIORITY) {
            Long size = redis.opsForList().size(q);
            total += size == null ? 0 : size;
        }
        return total;
    }

    public double failureRate() {
        String raw = redis.opsForValue().get(QueueKeys.FAILURE_RATE_KEY);
        if (raw == null) return 0.0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Event to publish (and optionally a DLQ push) as part of {@link #complete}. */
    public record Outcome(String eventType, Map<String, Object> eventFields, Long deadLetterId) {
    }

    /**
     * End-of-job Redis work in ONE pipelined round trip: optional DLQ push, ack the
     * processing-list entry, refresh the registry and publish the lifecycle event.
     */
    public void complete(String workerId, String rawId, Map<String, String> registryFields, Duration ttl, Outcome outcome) {
        String event = outcome == null ? null : toJson(outcome.eventType(), outcome.eventFields());
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                if (outcome != null && outcome.deadLetterId() != null) {
                    ops.opsForList().leftPush(QueueKeys.DLQ, Long.toString(outcome.deadLetterId()));
                }
                ops.opsForList().remove(processingKey(workerId), 1, rawId);
                ops.opsForHash().putAll(registryKey(workerId), registryFields);
                ops.expire(registryKey(workerId), ttl);
                if (event != null) ops.convertAndSend(QueueKeys.EVENTS_CHANNEL, event);
                return null;
            }
        });
    }

    // ---- liveness registry ------------------------------------------------------------

    /** HSET + EXPIRE pipelined into one round trip. */
    public void register(String workerId, Map<String, String> fields, Duration ttl) {
        String key = registryKey(workerId);
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.opsForHash().putAll(key, fields);
                ops.expire(key, ttl);
                return null;
            }
        });
    }

    public void unregister(String workerId) {
        redis.delete(registryKey(workerId));
    }

    public boolean isAlive(String workerId) {
        return Boolean.TRUE.equals(redis.hasKey(registryKey(workerId)));
    }

    // ---- recovery of processing lists owned by dead workers ---------------------------

    public List<String> processingListOwners() {
        List<String> owners = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(QueueKeys.PROCESSING_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(k -> owners.add(k.substring(QueueKeys.PROCESSING_PREFIX.length())));
        }
        return owners;
    }

    /**
     * Atomically takes ownership of a dead worker's processing list (RENAME), returns its IDs and
     * deletes it. RENAME fails if another reaper got there first, which makes this safe to run
     * from every worker container at once.
     */
    public List<String> drainProcessingList(String deadWorkerId) {
        String reapingKey = QueueKeys.REAPING_PREFIX + deadWorkerId + ":" + UUID.randomUUID();
        try {
            redis.rename(processingKey(deadWorkerId), reapingKey);
        } catch (RuntimeException alreadyTaken) {
            return List.of();
        }
        List<String> ids = redis.opsForList().range(reapingKey, 0, -1);
        redis.delete(reapingKey);
        return ids == null ? List.of() : ids;
    }

    // ---- events ----------------------------------------------------------------------

    public void publish(String type, Map<String, Object> fields) {
        redis.convertAndSend(QueueKeys.EVENTS_CHANNEL, toJson(type, fields));
    }

    private String toJson(String type, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("ts", System.currentTimeMillis());
        event.putAll(fields);
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
