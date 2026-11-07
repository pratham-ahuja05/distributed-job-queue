package com.pratham.workerservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pratham.workerservice.config.WorkerProperties;
import com.pratham.workerservice.queue.RedisJobQueue;
import com.pratham.workerservice.store.JobStore.ClaimedJob;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for real work (send email, render PDF, call a payment API...). It sleeps for
 * the requested duration and fails with the requested probability, so retries, the DLQ and
 * latency percentiles can be exercised and measured.
 *
 * Payload knobs: {"durationMs": 300, "failRate": 0.3}. Without them, the default duration and
 * the dashboard-controlled global failure rate (Redis settings:failure_rate) apply.
 */
@Component
public class JobExecutor {

    public static class TransientJobException extends Exception {
        public TransientJobException(String message) {
            super(message);
        }
    }

    private final ObjectMapper mapper;
    private final RedisJobQueue queue;
    private final WorkerProperties props;

    public JobExecutor(ObjectMapper mapper, RedisJobQueue queue, WorkerProperties props) {
        this.mapper = mapper;
        this.queue = queue;
        this.props = props;
    }

    public void execute(ClaimedJob job) throws TransientJobException, InterruptedException {
        long durationMs = props.defaultDurationMs();
        Double failRate = null;
        JsonNode payload = parse(job.payload());
        if (payload != null) {
            if (payload.hasNonNull("durationMs")) durationMs = Math.max(0, payload.get("durationMs").asLong());
            if (payload.hasNonNull("failRate")) failRate = payload.get("failRate").asDouble();
        }
        if (failRate == null) failRate = queue.failureRate();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (durationMs > 0) {
            // +/-20% so jobs don't finish in lockstep
            Thread.sleep(Math.max(0, (long) (durationMs * (0.8 + 0.4 * rnd.nextDouble()))));
        }
        if (rnd.nextDouble() < failRate) {
            throw new TransientJobException("simulated transient failure (failRate=" + failRate + ")");
        }
    }

    private JsonNode parse(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(payload);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
