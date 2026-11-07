package com.pratham.apiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxBatchSize       largest burst accepted by POST /api/jobs/batch
 * @param maxBacklog         reject new jobs (429) when this many are already waiting in Redis
 * @param rateLimitPer10s    write requests allowed per client IP per 10s (0 = off)
 * @param retentionMinutes   delete finished jobs older than this (0 = keep forever)
 * @param chaosCooldownSeconds minimum gap between "crash a worker" requests
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        int maxBatchSize,
        long maxBacklog,
        int rateLimitPer10s,
        int retentionMinutes,
        int chaosCooldownSeconds) {

    public AppProperties {
        if (maxBatchSize <= 0) maxBatchSize = 1000;
        if (maxBacklog <= 0) maxBacklog = 50_000;
        if (chaosCooldownSeconds < 0) chaosCooldownSeconds = 0;
    }
}
