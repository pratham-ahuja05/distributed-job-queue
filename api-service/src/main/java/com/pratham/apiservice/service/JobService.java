package com.pratham.apiservice.service;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.enums.JobStatus;
import com.pratham.apiservice.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final QueueService queueService;

    public JobService(JobRepository jobRepository,
                      QueueService queueService) {

        this.jobRepository = jobRepository;
        this.queueService = queueService;
    }

    public Job createJob(Job job) {

        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        Job savedJob = jobRepository.save(job);

        queueService.enqueueJob(savedJob);

        return savedJob;
    }
}