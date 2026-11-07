package com.pratham.apiservice.service;

import com.pratham.apiservice.queue.QueueKeys;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reads the liveness hashes worker threads keep in Redis (worker:{id}, short TTL).
 * A key that has expired means the thread or its whole container is gone.
 */
@Component
public class WorkerRegistry {

    private final StringRedisTemplate redis;

    public WorkerRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public List<Map<String, String>> liveWorkers() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(QueueKeys.WORKER_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        List<Map<String, String>> workers = new ArrayList<>();
        for (String key : keys) {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (raw.isEmpty()) continue;
            Map<String, String> w = new TreeMap<>();
            raw.forEach((k, v) -> w.put(String.valueOf(k), String.valueOf(v)));
            w.put("id", key.substring(QueueKeys.WORKER_PREFIX.length()));
            workers.add(w);
        }
        workers.sort(Comparator.comparing((Map<String, String> w) -> w.getOrDefault("instance", ""))
                .thenComparing(w -> w.get("id")));
        return workers;
    }

    public List<String> liveInstances() {
        return liveWorkers().stream()
                .map(w -> w.get("instance"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
