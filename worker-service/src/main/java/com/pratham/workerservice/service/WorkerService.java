package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WorkerService {

    private static final String HIGH = "high_priority_queue";
    private static final String MEDIUM = "medium_priority_queue";
    private static final String LOW = "low_priority_queue";
    private static final String PROCESSING_QUEUE = "processing_queue";
    private static final String DLQ = "dead_letter_queue";

    private final RedisTemplate<String, String> redisTemplate;
    private final JobRepository jobRepository;

    public WorkerService(RedisTemplate<String, String> redisTemplate, JobRepository jobRepository) {
        this.redisTemplate = redisTemplate;
        this.jobRepository = jobRepository;
    }

    @Transactional
    @Scheduled(fixedDelay = 2000)
    public void processJobs() {
        String jobId = fetchFromPriorityQueues();
        if (jobId == null) return;

        Long id = Long.parseLong(jobId);
        // LOCKED FETCH ensures no other worker or recovery service touches this row
        Job job = jobRepository.findByIdForUpdate(id).orElse(null);

        if (job == null) {
            redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, jobId);
            return;
        }

        // DOUBLE SAFETY: Check if it's actually ready to be picked up
        if (job.getStatus() != JobStatus.QUEUED && job.getStatus() != JobStatus.RETRYING) {
            System.out.println("Skipping job in status: " + job.getStatus() + " | ID: " + jobId);
            redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, jobId);
            return;
        }

        System.out.println("Worker started job: " + job.getId() + " | Priority: " + job.getPriority());

        try {
            // Update status and timestamp so RecoveryService knows we are active
            job.setStatus(JobStatus.PROCESSING);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);

            // --- ACTUAL WORK START ---
            Thread.sleep(3000); // Simulate processing (Email, PDF Gen, etc.)
            // --- ACTUAL WORK END ---

            job.setStatus(JobStatus.SUCCESS);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);

            // Successfully processed -> remove from tracking queue
            redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, jobId);
            System.out.println("Job completed successfully: " + jobId);

        } catch (Exception e) {
            handleJobFailure(job, jobId);
        }
    }

    private void handleJobFailure(Job job, String jobId) {
        System.out.println("Job failed: " + jobId);

        if (job.getRetryCount() < job.getMaxRetries()) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setStatus(JobStatus.RETRYING);

            // Exponential Backoff: 2, 4, 8, 16 seconds...
            int delay = (int) Math.pow(2, job.getRetryCount());
            job.setNextRetryAt(LocalDateTime.now().plusSeconds(delay));

            jobRepository.save(job);
            System.out.println("Retrying job " + jobId + " in " + delay + "s (Attempt " + job.getRetryCount() + ")");
        } else {
            // No retries left -> Move to Dead Letter Queue
            job.setStatus(JobStatus.DLQ);
            jobRepository.save(job);

            redisTemplate.opsForList().leftPush(DLQ, jobId);
            System.out.println("Max retries reached. Moved to DLQ: " + jobId);
        }

        // In either case, remove from the current processing tracking
        redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, jobId);
    }

    private String fetchFromPriorityQueues() {
        String jobId = redisTemplate.opsForList().rightPopAndLeftPush(HIGH, PROCESSING_QUEUE);
        if (jobId != null) return jobId;

        jobId = redisTemplate.opsForList().rightPopAndLeftPush(MEDIUM, PROCESSING_QUEUE);
        if (jobId != null) return jobId;

        return redisTemplate.opsForList().rightPopAndLeftPush(LOW, PROCESSING_QUEUE);
    }
}
