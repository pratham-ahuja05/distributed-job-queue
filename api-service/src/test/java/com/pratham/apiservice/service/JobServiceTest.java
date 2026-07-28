package com.pratham.apiservice.service;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.enums.JobStatus;
import com.pratham.apiservice.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private QueueService queueService;

    @InjectMocks
    private JobService jobService;

    @Test
    void createJobSetsLifecycleDefaultsAndEnqueues() {
        Job input = Job.builder()
                .type("email")
                .payload("{\"to\":\"test@example.com\"}")
                .priority(1)
                .maxRetries(3)
                .build();

        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        Job created = jobService.createJob(input);

        assertEquals(101L, created.getId());
        assertEquals(JobStatus.QUEUED, created.getStatus());
        assertEquals(0, created.getRetryCount());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());
        verify(queueService).enqueueJob(created);
    }
}
