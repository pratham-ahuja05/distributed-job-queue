package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import org.springframework.data.redis.core.ListOperations;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ListOperations<String, String> listOperations;

    @InjectMocks
    private WorkerService workerService;

    @Test
    void shouldRetryJobWhenFailureAndRetriesLeft() {

        // Arrange
        Job job = new Job();
        job.setRetryCount(0);
        job.setMaxRetries(3);

        String jobId = "1";

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // Act
        workerService.handleJobFailure(job, jobId);

        // Assert
        assertEquals(1, job.getRetryCount());
        assertEquals(JobStatus.RETRYING, job.getStatus());
        assertNotNull(job.getNextRetryAt());

        verify(jobRepository, times(1)).save(job);
    }

    @Test
    void shouldMoveJobToDLQWhenMaxRetriesExceeded() {

        // Arrange
        Job job = new Job();
        job.setRetryCount(3);
        job.setMaxRetries(3);

        String jobId = "1";

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // Act
        workerService.handleJobFailure(job, jobId);

        // Assert
        assertEquals(JobStatus.DLQ, job.getStatus());

        verify(jobRepository, times(1)).save(job);

        verify(listOperations, times(1))
                .leftPush("dead_letter_queue", jobId);
    }

    @Test
    void shouldThrowExceptionWhenDatabaseSaveFails() {

        // Arrange
        Job job = new Job();
        job.setRetryCount(0);
        job.setMaxRetries(3);

        String jobId = "1";



        when(jobRepository.save(any(Job.class)))
                .thenThrow(new RuntimeException("DB failure"));

        // Act + Assert
        assertThrows(RuntimeException.class, () -> {
            workerService.handleJobFailure(job, jobId);
        });
    }

    @Test
    void shouldThrowExceptionWhenRedisFailsDuringDLQ() {

        // Arrange
        Job job = new Job();
        job.setRetryCount(3);
        job.setMaxRetries(3);

        String jobId = "1";

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        doThrow(new RuntimeException("Redis down"))
                .when(listOperations).leftPush(anyString(), anyString());

        // Act + Assert
        assertThrows(RuntimeException.class, () -> {
            workerService.handleJobFailure(job, jobId);
        });
    }

    @Test
    void shouldHandleInvalidRetryStateGracefully() {

        // Arrange
        Job job = new Job();
        job.setRetryCount(5); // invalid
        job.setMaxRetries(3);

        String jobId = "1";

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // Act
        workerService.handleJobFailure(job, jobId);

        // Assert
        assertEquals(JobStatus.DLQ, job.getStatus());
    }
}
