package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosListenerTest {

    private static WorkerProperties props(boolean enabled) {
        return new WorkerProperties("worker-a", 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, enabled, false);
    }

    private static DefaultMessage msg(String body) {
        return new DefaultMessage("chaos:crash".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void haltsOnlyTheTargetedInstance() {
        AtomicInteger code = new AtomicInteger(-1);
        ChaosListener listener = new ChaosListener(props(true), code::set);

        listener.onMessage(msg("worker-b"), null);
        assertThat(code.get()).isEqualTo(-1);

        listener.onMessage(msg("worker-a"), null);
        assertThat(code.get()).isEqualTo(137);
    }

    @Test
    void ignoresRequestsWhenChaosIsDisabled() {
        AtomicInteger code = new AtomicInteger(-1);
        new ChaosListener(props(false), code::set).onMessage(msg("worker-a"), null);
        assertThat(code.get()).isEqualTo(-1);
    }

    @Test
    void defaultInstanceIdIsGeneratedAndUnique() {
        WorkerProperties a = new WorkerProperties(null, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, false, null);
        WorkerProperties b = new WorkerProperties("", 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, false, null);
        assertThat(a.instanceId()).isNotBlank().isNotEqualTo(b.instanceId());
        assertThat(a.concurrency()).isEqualTo(4);
        assertThat(a.autoStart()).isTrue();
        assertThat(a.defaultDurationMs()).isEqualTo(500);
    }
}
