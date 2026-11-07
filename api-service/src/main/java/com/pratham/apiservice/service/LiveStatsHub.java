package com.pratham.apiservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pratham.apiservice.domain.JobStatus;
import com.pratham.apiservice.queue.JobQueue;
import com.pratham.apiservice.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Turns the Redis event stream into a once-per-second snapshot for the dashboard (SSE)
 * and for the Prometheus gauges. Keeps a 2-minute rolling window in memory.
 */
@Service
public class LiveStatsHub implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(LiveStatsHub.class);
    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_EVENTS_PER_TICK = 100;
    private static final int RECENT_EVENTS = 40;

    // bucket columns
    static final int SUBMITTED = 0, SUCCESS = 1, RETRY = 2, DLQ = 3, RECOVERED = 4;

    private final JobQueue queue;
    private final JobRepository repository;
    private final WorkerRegistry workers;
    private final SettingsService settings;
    private final ObjectMapper mapper;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> pendingEvents = new ConcurrentLinkedQueue<>();
    private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();
    private final ConcurrentSkipListMap<Long, long[]> buckets = new ConcurrentSkipListMap<>();
    private final ConcurrentLinkedDeque<long[]> latencies = new ConcurrentLinkedDeque<>(); // [tsMillis, latencyMs]

    private volatile Map<String, Object> latest = Map.of();
    private volatile Map<String, Long> latestQueues = Map.of();
    private volatile Map<String, Long> latestStatuses = Map.of();

    public LiveStatsHub(JobQueue queue, JobRepository repository, WorkerRegistry workers,
                        SettingsService settings, ObjectMapper mapper, MeterRegistry meters) {
        this.queue = queue;
        this.repository = repository;
        this.workers = workers;
        this.settings = settings;
        this.mapper = mapper;
        for (String q : List.of("HIGH", "MEDIUM", "LOW", "DLQ")) {
            Gauge.builder("jobqueue_depth", () -> latestQueues.getOrDefault(q, 0L))
                    .tag("queue", q).description("Job IDs waiting in Redis").register(meters);
        }
        for (JobStatus s : JobStatus.values()) {
            Gauge.builder("jobs_by_status", () -> latestStatuses.getOrDefault(s.name(), 0L))
                    .tag("status", s.name()).description("Jobs in Postgres by status").register(meters);
        }
    }

    // ---- inbound events ---------------------------------------------------------------

    @Override
    public void onMessage(Message message, byte[] pattern) {
        record(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    void record(String json) {
        Map<String, Object> event;
        try {
            event = mapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.warn("Ignoring malformed event: {}", json);
            return;
        }
        String type = String.valueOf(event.get("type"));
        long ts = event.get("ts") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        long[] bucket = buckets.computeIfAbsent(ts / 1000, k -> new long[5]);
        synchronized (bucket) {
            switch (type) {
                case "SUBMITTED" -> bucket[SUBMITTED] += event.get("count") instanceof Number n ? n.longValue() : 1;
                case "SUCCESS" -> bucket[SUCCESS]++;
                case "RETRY" -> bucket[RETRY]++;
                case "DLQ" -> bucket[DLQ]++;
                case "RECOVERED" -> bucket[RECOVERED]++;
                default -> {
                }
            }
        }
        if ("SUCCESS".equals(type) && event.get("latencyMs") instanceof Number latency) {
            latencies.addLast(new long[]{ts, latency.longValue()});
        }
        pendingEvents.add(event);
    }

    // ---- outbound snapshots -----------------------------------------------------------

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        Map<String, Object> current = latest;
        if (!current.isEmpty()) {
            Map<String, Object> initial = new LinkedHashMap<>(current);
            synchronized (recentEvents) {
                initial.put("events", new ArrayList<>(recentEvents));
            }
            send(emitter, initial);
        }
        return emitter;
    }

    public Map<String, Object> latest() {
        return latest.isEmpty() ? buildSnapshot(List.of()) : latest;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        List<Map<String, Object>> fresh = new ArrayList<>();
        Map<String, Object> e;
        while ((e = pendingEvents.poll()) != null) {
            fresh.add(e);
        }
        if (fresh.size() > MAX_EVENTS_PER_TICK) {
            fresh = fresh.subList(fresh.size() - MAX_EVENTS_PER_TICK, fresh.size());
        }
        synchronized (recentEvents) {
            for (Map<String, Object> ev : fresh) {
                recentEvents.addLast(ev);
                if (recentEvents.size() > RECENT_EVENTS) recentEvents.removeFirst();
            }
        }
        try {
            latest = buildSnapshot(fresh);
        } catch (RuntimeException ex) {
            log.warn("Snapshot failed: {}", ex.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, latest);
        }
        prune();
    }

    Map<String, Object> buildSnapshot(List<Map<String, Object>> fresh) {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;

        Map<String, Long> queues = queue.depths();
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) statuses.put(s.name(), 0L);
        for (Object[] row : repository.countByStatus()) {
            statuses.put(((JobStatus) row[0]).name(), ((Number) row[1]).longValue());
        }
        latestQueues = queues;
        latestStatuses = statuses;

        List<Map<String, Object>> series = new ArrayList<>(WINDOW_SECONDS);
        long success10s = 0;
        for (long sec = nowSec - WINDOW_SECONDS; sec < nowSec; sec++) {
            long[] b = buckets.getOrDefault(sec, new long[5]);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", sec * 1000);
            synchronized (b) {
                point.put("submitted", b[SUBMITTED]);
                point.put("success", b[SUCCESS]);
                point.put("retry", b[RETRY]);
                point.put("dlq", b[DLQ]);
                point.put("recovered", b[RECOVERED]);
                if (sec >= nowSec - 10) success10s += b[SUCCESS];
            }
            series.add(point);
        }

        long cutoff = nowMs - WINDOW_SECONDS * 1000L;
        long[] window = latencies.stream().filter(l -> l[0] >= cutoff).mapToLong(l -> l[1]).sorted().toArray();
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50", percentile(window, 0.50));
        latency.put("p95", percentile(window, 0.95));
        latency.put("p99", percentile(window, 0.99));
        latency.put("samples", window.length);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ts", nowMs);
        snap.put("queues", queues);
        snap.put("statuses", statuses);
        snap.put("workers", workers.liveWorkers());
        snap.put("series", series);
        snap.put("throughputPerSec", success10s / 10.0);
        snap.put("latencyMs", latency);
        snap.put("failureRate", settings.failureRate());
        snap.put("events", fresh);
        return snap;
    }

    static Long percentile(long[] sorted, double q) {
        if (sorted.length == 0) return null;
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.min(Math.max(idx, 0), sorted.length - 1)];
    }

    int subscriberCount() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(data));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
        }
    }

    private void prune() {
        long oldestSec = System.currentTimeMillis() / 1000 - 2L * WINDOW_SECONDS;
        buckets.headMap(oldestSec).clear();
        long oldestMs = oldestSec * 1000;
        while (!latencies.isEmpty() && latencies.peekFirst()[0] < oldestMs) {
            latencies.pollFirst();
        }
    }
}
