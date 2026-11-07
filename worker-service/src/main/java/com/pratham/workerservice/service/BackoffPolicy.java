package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "full jitter": delay = random(0, min(cap, base * 2^attempt)).
 *
 * Without jitter, every job that failed in the same outage retries at the same instant and
 * hammers the dependency again (thundering herd). Full jitter spreads retries uniformly
 * over the window while keeping the exponential envelope.
 */
@Component
public class BackoffPolicy {

    private final long baseMs;
    private final long capMs;
    private final DoubleSupplier random;

    @Autowired
    public BackoffPolicy(WorkerProperties props) {
        this(props.retryBaseDelayMs(), props.retryMaxDelayMs(), () -> ThreadLocalRandom.current().nextDouble());
    }

    BackoffPolicy(long baseMs, long capMs, DoubleSupplier random) {
        this.baseMs = baseMs;
        this.capMs = capMs;
        this.random = random;
    }

    /** Upper bound for the given retry attempt (1-based). */
    public long ceilingFor(int attempt) {
        int exp = Math.min(Math.max(attempt, 0), 30);
        return Math.min(capMs, baseMs * (1L << exp));
    }

    public long delayFor(int attempt) {
        return (long) (random.getAsDouble() * ceilingFor(attempt));
    }
}
