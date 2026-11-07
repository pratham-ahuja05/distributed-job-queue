package com.pratham.apiservice.service;

import com.pratham.apiservice.config.AppProperties;
import com.pratham.apiservice.queue.QueueKeys;
import com.pratham.apiservice.service.ApiExceptions.NotFoundException;
import com.pratham.apiservice.service.ApiExceptions.TooManyRequestsException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-only fault injection. Asks one random worker container to halt the JVM abruptly
 * (no graceful shutdown, in-flight jobs abandoned) so viewers can watch recovery happen.
 * Works over Redis pub/sub, so the API never needs access to the Docker socket.
 */
@Service
public class ChaosService {

    private final StringRedisTemplate redis;
    private final WorkerRegistry registry;
    private final EventPublisher events;
    private final AppProperties props;
    private final AtomicLong lastCrashAt = new AtomicLong(0);

    public ChaosService(StringRedisTemplate redis, WorkerRegistry registry, EventPublisher events, AppProperties props) {
        this.redis = redis;
        this.registry = registry;
        this.events = events;
        this.props = props;
    }

    public String crashRandomWorker() {
        long now = System.currentTimeMillis();
        long last = lastCrashAt.get();
        long cooldownMs = props.chaosCooldownSeconds() * 1000L;
        if (now - last < cooldownMs || !lastCrashAt.compareAndSet(last, now)) {
            throw new TooManyRequestsException("a worker was crashed recently; wait for it to recover");
        }
        List<String> instances = registry.liveInstances();
        if (instances.isEmpty()) {
            lastCrashAt.set(last);
            throw new NotFoundException("no live worker instances");
        }
        String target = instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        redis.convertAndSend(QueueKeys.CHAOS_CHANNEL, target);
        events.publish("CRASH_REQUESTED", Map.of("instance", target));
        return target;
    }
}
