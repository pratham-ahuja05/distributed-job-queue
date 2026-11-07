package com.pratham.workerservice.service;

import com.pratham.workerservice.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/**
 * Demo fault injection: when the dashboard names this instance on the chaos channel, halt the
 * JVM immediately - no shutdown hooks, no graceful drain - exactly like a kill -9 or an OOM kill.
 * Docker's restart policy brings the container back; RecoveryService rescues its jobs.
 */
@Component
public class ChaosListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChaosListener.class);

    private final WorkerProperties props;
    private final IntConsumer halter;

    @Autowired
    public ChaosListener(WorkerProperties props) {
        this(props, code -> Runtime.getRuntime().halt(code));
    }

    ChaosListener(WorkerProperties props, IntConsumer halter) {
        this.props = props;
        this.halter = halter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String target = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        if (props.chaosEnabled() && props.instanceId().equals(target)) {
            log.error("CHAOS: crash requested for {} - halting JVM now", target);
            halter.accept(137);
        }
    }
}
