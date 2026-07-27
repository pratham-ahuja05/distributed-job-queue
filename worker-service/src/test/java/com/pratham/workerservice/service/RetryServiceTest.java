package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @InjectMocks
    private RetryService retryService;

    @Test
    void retryJobsRequeuesDueRetryingJob() {
        Job job = new Job();
        job.setId(42L);
        job.setStatus(JobStatus.RETRYING);
        job.setPriority(2);
        job.setNextRetryAt(LocalDateTime.now().minusSeconds(1));

        when(jobRepository.findByStatus(JobStatus.RETRYING)).thenReturn(List.of(job));
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        retryService.retryJobs();

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertNull(job.getNextRetryAt());
        verify(jobRepository).save(job);
        verify(listOperations).leftPush("medium_priority_queue", "42");
    }

    @Test
    void retryJobsSkipsJobsNotReadyForRetry() {
        Job job = new Job();
        job.setId(7L);
        job.setStatus(JobStatus.RETRYING);
        job.setPriority(1);
        job.setNextRetryAt(LocalDateTime.now().plusSeconds(30));

        when(jobRepository.findByStatus(JobStatus.RETRYING)).thenReturn(List.of(job));

        retryService.retryJobs();

        assertEquals(JobStatus.RETRYING, job.getStatus());
        verify(jobRepository, never()).save(job);
        verify(listOperations, never()).leftPush("high_priority_queue", "7");
    }
}
