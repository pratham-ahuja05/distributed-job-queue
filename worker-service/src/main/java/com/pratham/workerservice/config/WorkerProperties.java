package com.pratham.workerservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * @param instanceId          unique per JVM boot; defaults to HOSTNAME plus a random suffix so a
 *                            restarted container never reuses its predecessor's identity
 * @param concurrency         worker threads in this container
 * @param idleSleepMs         back-off when every queue is empty
 * @param heartbeatIntervalMs how often in-flight jobs and the Redis registry are refreshed
 * @param staleAfterSeconds   a PROCESSING job whose heartbeat is older than this is reclaimed
 * @param registryTtlSeconds  TTL of the worker:{id} liveness key
 * @param retryBaseDelayMs    backoff base (attempt n waits up to base * 2^n)
 * @param retryMaxDelayMs     backoff cap
 * @param defaultDurationMs   simulated work time when the payload doesn't specify one
 * @param sweepBatchSize      rows claimed per scheduler pass (retry / recovery)
 * @param orphanAfterSeconds  QUEUED rows untouched this long while Redis is empty get re-enqueued
 * @param chaosEnabled        honour "crash this instance" requests from the demo dashboard
 * @param autoStart           start the worker threads with the application context (tests turn this off)
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        String instanceId,
        int concurrency,
        long idleSleepMs,
        long heartbeatIntervalMs,
        int staleAfterSeconds,
        int registryTtlSeconds,
        long retryBaseDelayMs,
        long retryMaxDelayMs,
        int defaultDurationMs,
        int sweepBatchSize,
        int orphanAfterSeconds,
        boolean chaosEnabled,
        Boolean autoStart) {

    public WorkerProperties {
        if (instanceId == null || instanceId.isBlank()) {
            String host = System.getenv().getOrDefault("HOSTNAME", "worker");
            instanceId = host + "-" + UUID.randomUUID().toString().substring(0, 4);
        }
        if (concurrency <= 0) concurrency = 4;
        if (idleSleepMs <= 0) idleSleepMs = 100;
        if (heartbeatIntervalMs <= 0) heartbeatIntervalMs = 2000;
        if (staleAfterSeconds <= 0) staleAfterSeconds = 15;
        if (registryTtlSeconds <= 0) registryTtlSeconds = 10;
        if (retryBaseDelayMs <= 0) retryBaseDelayMs = 1000;
        if (retryMaxDelayMs <= 0) retryMaxDelayMs = 60_000;
        if (defaultDurationMs < 0) defaultDurationMs = 500;
        if (sweepBatchSize <= 0) sweepBatchSize = 100;
        if (orphanAfterSeconds <= 0) orphanAfterSeconds = 30;
        if (autoStart == null) autoStart = true;
    }
}
