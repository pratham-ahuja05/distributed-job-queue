package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@Service
public class WorkerService {

    private static final String HIGH = "high_priority_queue";
    private static final String MEDIUM = "medium_priority_queue";
    private static final String LOW = "low_priority_queue";
    private static final String PROCESSING_QUEUE = "processing_queue";
    private static final String DLQ = "dead_letter_queue";
    private static final int DEFAULT_HIGH_WEIGHT = 70;
    private static final int DEFAULT_MEDIUM_WEIGHT = 20;
    private static final int DEFAULT_LOW_WEIGHT = 10;
    private static final int DEFAULT_EMPTY_SELECTION_RETRIES = 3;
    private static final String[] PRIORITY_QUEUES = {HIGH, MEDIUM, LOW};

    private final RedisTemplate<String, String> redisTemplate;
    private final JobRepository jobRepository;
    private final AtomicInteger fallbackStartIndex = new AtomicInteger(0);
    private final LongAdder emptyPollCount = new LongAdder();
    private final LongAdder highDequeuedCount = new LongAdder();
    private final LongAdder mediumDequeuedCount = new LongAdder();
    private final LongAdder lowDequeuedCount = new LongAdder();

    @Value("${worker.queue.weights.high:70}")
    private int highQueueWeight = DEFAULT_HIGH_WEIGHT;

    @Value("${worker.queue.weights.medium:20}")
    private int mediumQueueWeight = DEFAULT_MEDIUM_WEIGHT;

    @Value("${worker.queue.weights.low:10}")
    private int lowQueueWeight = DEFAULT_LOW_WEIGHT;

    @Value("${worker.queue.selection.empty-retries:3}")
    private int emptySelectionRetries = DEFAULT_EMPTY_SELECTION_RETRIES;

    private volatile QueueSelectionStrategy queueSelectionStrategy;

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

    String fetchFromPriorityQueues() {
        QueueSelectionStrategy selectionStrategy = getQueueSelectionStrategy();

        int boundedRetries = Math.max(0, emptySelectionRetries);
        for (int attempt = 0; attempt <= boundedRetries; attempt++) {
            String selectedQueue = selectionStrategy.selectQueue();
            String jobId = redisTemplate.opsForList().rightPopAndLeftPush(selectedQueue, PROCESSING_QUEUE);
            if (jobId != null) {
                recordDequeue(selectedQueue);
                return jobId;
            }
        }

        int queueCount = selectionStrategy.queueCount();
        int startIndex = Math.floorMod(fallbackStartIndex.getAndIncrement(), queueCount);
        for (int i = 0; i < queueCount; i++) {
            String queueName = selectionStrategy.queueAt((startIndex + i) % queueCount);
            String jobId = redisTemplate.opsForList().rightPopAndLeftPush(queueName, PROCESSING_QUEUE);
            if (jobId != null) {
                recordDequeue(queueName);
                return jobId;
            }
        }

        emptyPollCount.increment();
        long emptyPolls = emptyPollCount.sum();
        if (emptyPolls % 50 == 0) {
            System.out.println("Worker empty polls: " + emptyPolls
                    + " | dequeues high=" + highDequeuedCount.sum()
                    + ", medium=" + mediumDequeuedCount.sum()
                    + ", low=" + lowDequeuedCount.sum());
        }
        return null;
    }

    private QueueSelectionStrategy getQueueSelectionStrategy() {
        QueueSelectionStrategy current = queueSelectionStrategy;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (queueSelectionStrategy == null) {
                try {
                    queueSelectionStrategy = new WeightedQueueSelector(
                            PRIORITY_QUEUES,
                            new int[]{highQueueWeight, mediumQueueWeight, lowQueueWeight}
                    );
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid worker queue weights configured. Falling back to defaults. " + e.getMessage());
                    queueSelectionStrategy = new WeightedQueueSelector(
                            PRIORITY_QUEUES,
                            new int[]{DEFAULT_HIGH_WEIGHT, DEFAULT_MEDIUM_WEIGHT, DEFAULT_LOW_WEIGHT}
                    );
                }
            }
            return queueSelectionStrategy;
        }
    }

    private void recordDequeue(String queueName) {
        if (HIGH.equals(queueName)) {
            highDequeuedCount.increment();
        } else if (MEDIUM.equals(queueName)) {
            mediumDequeuedCount.increment();
        } else if (LOW.equals(queueName)) {
            lowDequeuedCount.increment();
        }
    }
}
