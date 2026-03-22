package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RecoveryService {

    private static final String JOB_QUEUE = "job_queue";
    private static final String PROCESSING_QUEUE = "processing_queue";

    private final JobRepository jobRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public RecoveryService(JobRepository jobRepository,
                           RedisTemplate<String, String> redisTemplate) {
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelay = 10000)
    public void recoverStuckJobs() {

        LocalDateTime threshold = LocalDateTime.now().minusSeconds(15);

        List<Job> stuckJobs = jobRepository
                .findStuckJobs(JobStatus.PROCESSING, threshold);

        for (Job job : stuckJobs) {

            // ️ Only recover true stuck jobs
            if (job.getStatus() != JobStatus.PROCESSING) continue;

            System.out.println("Recovering stuck job: " + job.getId());

            // Reset state safely
            job.setStatus(JobStatus.QUEUED);
            jobRepository.save(job);

            String jobId = job.getId().toString();

            // Push back to queue
            redisTemplate.opsForList().leftPush(JOB_QUEUE, jobId);

            // Clean processing queue
            redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, jobId);
        }
    }
}