package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    @Test
    void ceilingGrowsExponentiallyUntilCap() {
        BackoffPolicy policy = new BackoffPolicy(1000, 10_000, () -> 0.5);
        assertThat(policy.ceilingFor(1)).isEqualTo(2000);
        assertThat(policy.ceilingFor(2)).isEqualTo(4000);
        assertThat(policy.ceilingFor(3)).isEqualTo(8000);
        assertThat(policy.ceilingFor(4)).isEqualTo(10_000);
        assertThat(policy.ceilingFor(60)).isEqualTo(10_000); // no overflow
    }

    @Test
    void fullJitterScalesTheCeilingByTheRandomFraction() {
        assertThat(new BackoffPolicy(1000, 60_000, () -> 0.0).delayFor(3)).isZero();
        assertThat(new BackoffPolicy(1000, 60_000, () -> 0.5).delayFor(3)).isEqualTo(4000);
        assertThat(new BackoffPolicy(1000, 60_000, () -> 0.999).delayFor(3)).isEqualTo(7992);
    }

    @Test
    void realRandomSpreadsRetriesInsteadOfSynchronisingThem() {
        BackoffPolicy policy = new BackoffPolicy(props());
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long d = policy.delayFor(2);
            assertThat(d).isBetween(0L, 4000L);
            distinct.add(d);
        }
        // Without jitter all 200 would be identical (thundering herd).
        assertThat(distinct.size()).isGreaterThan(150);
    }

    private static WorkerProperties props() {
        return new WorkerProperties("t", 1, 1, 1, 1, 1, 1000, 60_000, 0, 1, 1, false, false);
    }
}
