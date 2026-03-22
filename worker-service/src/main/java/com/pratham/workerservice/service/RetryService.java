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
public class RetryService {

    private final JobRepository jobRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public RetryService(JobRepository jobRepository,
                        RedisTemplate<String, String> redisTemplate) {
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    public void retryJobs() {

        List<Job> jobs = jobRepository.findByStatus(JobStatus.RETRYING);

        for (Job job : jobs) {

            // safety check
            if (job.getStatus() != JobStatus.RETRYING) continue;

            if (job.getNextRetryAt() != null &&
                    job.getNextRetryAt().isBefore(LocalDateTime.now())) {

                System.out.println("Retrying job: " + job.getId());

                job.setStatus(JobStatus.QUEUED);
                job.setNextRetryAt(null); //
                jobRepository.save(job);

                String queueName;

                if (job.getPriority() == 1) {
                    queueName = "high_priority_queue";
                } else if (job.getPriority() == 2) {
                    queueName = "medium_priority_queue";
                } else {
                    queueName = "low_priority_queue";
                }

                redisTemplate.opsForList()
                        .leftPush(queueName, job.getId().toString());
            }
        }
    }
}