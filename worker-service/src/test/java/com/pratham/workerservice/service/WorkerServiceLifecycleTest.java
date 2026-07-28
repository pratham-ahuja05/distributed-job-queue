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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceLifecycleTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private JobRepository jobRepository;

    @InjectMocks
    private WorkerService workerService;

    @Test
    void processJobsCompletesQueuedJob() {
        Job job = jobWith(1L, JobStatus.QUEUED, 0, 2);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush("high_priority_queue", "processing_queue")).thenReturn("1");
        when(jobRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        workerService.processJobs();

        assertEquals(JobStatus.SUCCESS, job.getStatus());
        verify(jobRepository, times(2)).save(job);
        verify(listOperations).remove("processing_queue", 1, "1");
        verify(listOperations, never()).leftPush("dead_letter_queue", "1");
    }

    @Test
    void processJobsMovesFailedJobToRetry() {
        Job job = jobWith(2L, JobStatus.QUEUED, 0, 2);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush("high_priority_queue", "processing_queue")).thenReturn("2");
        when(jobRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("processing failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(job);

        workerService.processJobs();

        assertEquals(JobStatus.RETRYING, job.getStatus());
        assertEquals(1, job.getRetryCount());
        assertNotNull(job.getNextRetryAt());
        verify(listOperations).remove("processing_queue", 1, "2");
        verify(listOperations, never()).leftPush("dead_letter_queue", "2");
    }

    @Test
    void processJobsMovesJobToDlqWhenRetriesExhausted() {
        Job job = jobWith(3L, JobStatus.QUEUED, 1, 1);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush("high_priority_queue", "processing_queue")).thenReturn("3");
        when(jobRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("processing failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(job);

        workerService.processJobs();

        assertEquals(JobStatus.DLQ, job.getStatus());
        verify(listOperations).leftPush("dead_letter_queue", "3");
        verify(listOperations).remove("processing_queue", 1, "3");
    }

    @Test
    void processJobsHandlesMultipleDistinctJobsWithoutDuplicateProcessing() {
        Job highPriorityJob = jobWith(10L, JobStatus.QUEUED, 0, 1);
        Job mediumPriorityJob = jobWith(20L, JobStatus.QUEUED, 0, 1);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush("high_priority_queue", "processing_queue"))
                .thenReturn("10", (String) null);
        when(listOperations.rightPopAndLeftPush("medium_priority_queue", "processing_queue"))
                .thenReturn("20");
        when(jobRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(highPriorityJob));
        when(jobRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(mediumPriorityJob));

        doThrow(new RuntimeException("job one failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(highPriorityJob);

        doThrow(new RuntimeException("job two failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(mediumPriorityJob);

        workerService.processJobs();
        workerService.processJobs();

        verify(jobRepository).findByIdForUpdate(10L);
        verify(jobRepository).findByIdForUpdate(20L);
        verify(listOperations).remove("processing_queue", 1, "10");
        verify(listOperations).remove("processing_queue", 1, "20");
        verify(jobRepository, times(2)).save(highPriorityJob);
        verify(jobRepository, times(2)).save(mediumPriorityJob);
    }

    private Job jobWith(Long id, JobStatus status, int retryCount, int maxRetries) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setPriority(1);
        job.setRetryCount(retryCount);
        job.setMaxRetries(maxRetries);
        return job;
    }
}
