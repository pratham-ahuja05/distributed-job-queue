package com.pratham.apiservice.service;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.enums.JobStatus;
import com.pratham.apiservice.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private QueueService queueService;

    @InjectMocks
    private JobService jobService;

    @Test
    void shouldCreateJobAndPushToQueue() {

        // Arrange
        Job job = new Job();
        job.setType("EMAIL");

        // define mock behavior
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Job result = jobService.createJob(job);

        // Assert
        assertEquals(JobStatus.QUEUED, result.getStatus());
        assertEquals(0, result.getRetryCount());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        // verify interactions
        verify(jobRepository, times(1)).save(any(Job.class));
        verify(queueService, times(1)).enqueueJob(any(Job.class));
    }
}