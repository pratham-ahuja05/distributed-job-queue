package com.pratham.apiservice.web.dto;

import com.pratham.apiservice.domain.Job;
import com.pratham.apiservice.domain.JobStatus;
import com.pratham.apiservice.domain.Priority;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }

    public record CreateJobRequest(
            @NotBlank @Size(max = 64) String type,
            @NotNull Priority priority,
            Map<String, Object> payload,
            @Min(0) @Max(10) Integer maxRetries) {
    }

    /**
     * Submit many synthetic jobs at once (dashboard "burst" button and load tests).
     * priority == null means a mixed workload: 20% HIGH, 30% MEDIUM, 50% LOW.
     */
    public record BatchRequest(
            @Min(1) int count,
            @Size(max = 64) String type,
            Priority priority,
            @Min(0) @Max(10) Integer maxRetries,
            @Min(0) @Max(10_000) Integer durationMs,
            @DecimalMin("0.0") @DecimalMax("1.0") Double failRate) {
    }

    public record BatchResponse(int submitted, long firstId, long lastId) {
    }

    public record JobView(
            long id, String type, String payload, JobStatus status, Priority priority,
            int retryCount, int maxRetries, int attempts, int recoveredCount,
            String workerId, String lastError,
            Instant createdAt, Instant updatedAt, Instant startedAt, Instant completedAt,
            Instant nextRetryAt) {

        public static JobView of(Job j) {
            return new JobView(j.getId(), j.getType(), j.getPayload(), j.getStatus(),
                    Priority.ofLevel(j.getPriority()), j.getRetryCount(), j.getMaxRetries(),
                    j.getAttempts(), j.getRecoveredCount(), j.getWorkerId(), j.getLastError(),
                    j.getCreatedAt(), j.getUpdatedAt(), j.getStartedAt(), j.getCompletedAt(),
                    j.getNextRetryAt());
        }
    }

    public record SettingsView(double failureRate) {
    }

    public record SettingsUpdate(@NotNull @DecimalMin("0.0") @DecimalMax("0.9") Double failureRate) {
    }

    public record CrashResponse(String instance) {
    }

    public record RedriveResponse(int requeued) {
    }

    /** Aggregates for one benchmark run (all jobs sharing a `type`). Latencies in ms. */
    public record RunStats(
            String type, long total, long success, long dlq, long pending,
            Double p50LatencyMs, Double p95LatencyMs, Double p99LatencyMs,
            Double p50WaitMs, Double p95WaitMs,
            double avgAttempts, long reExecutions, long recovered,
            Double spanSeconds, Double throughputPerSec) {
    }
}
