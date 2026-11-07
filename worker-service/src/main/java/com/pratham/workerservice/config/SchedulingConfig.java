package com.pratham.workerservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Heartbeat, retry release and recovery sweeps. Integration tests switch this off and call them directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
