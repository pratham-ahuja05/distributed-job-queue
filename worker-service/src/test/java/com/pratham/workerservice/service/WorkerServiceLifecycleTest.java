package com.pratham.workerservice.service;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(workerService, "queueSelectionStrategy", fixedSelection(HIGH_QUEUE));
        ReflectionTestUtils.setField(workerService, "emptySelectionRetries", 0);
    }

    private static final String HIGH_QUEUE = "high_priority_queue";
    private static final String MEDIUM_QUEUE = "medium_priority_queue";
    private static final String LOW_QUEUE = "low_priority_queue";
    private static final String PROCESSING_QUEUE = "processing_queue";

    @Test
    void processJobsCompletesQueuedJob() {
        Job job = jobWith(1L, JobStatus.QUEUED, 0, 2);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE)).thenReturn("1");
        when(jobRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        workerService.processJobs();

        assertEquals(JobStatus.SUCCESS, job.getStatus());
        verify(jobRepository, times(2)).save(job);
        verify(listOperations).remove(PROCESSING_QUEUE, 1, "1");
        verify(listOperations, never()).leftPush("dead_letter_queue", "1");
    }

    @Test
    void processJobsMovesFailedJobToRetry() {
        Job job = jobWith(2L, JobStatus.QUEUED, 0, 2);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE)).thenReturn("2");
        when(jobRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("processing failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(job);

        workerService.processJobs();

        assertEquals(JobStatus.RETRYING, job.getStatus());
        assertEquals(1, job.getRetryCount());
        assertNotNull(job.getNextRetryAt());
        verify(listOperations).remove(PROCESSING_QUEUE, 1, "2");
        verify(listOperations, never()).leftPush("dead_letter_queue", "2");
    }

    @Test
    void processJobsMovesJobToDlqWhenRetriesExhausted() {
        Job job = jobWith(3L, JobStatus.QUEUED, 1, 1);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE)).thenReturn("3");
        when(jobRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("processing failed"))
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(jobRepository).save(job);

        workerService.processJobs();

        assertEquals(JobStatus.DLQ, job.getStatus());
        verify(listOperations).leftPush("dead_letter_queue", "3");
        verify(listOperations).remove(PROCESSING_QUEUE, 1, "3");
    }

    @Test
    void processJobsHandlesMultipleDistinctJobsWithoutDuplicateProcessing() {
        Job highPriorityJob = jobWith(10L, JobStatus.QUEUED, 0, 1);
        Job mediumPriorityJob = jobWith(20L, JobStatus.QUEUED, 0, 1);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE))
                .thenReturn("10", (String) null);
        when(listOperations.rightPopAndLeftPush(MEDIUM_QUEUE, PROCESSING_QUEUE))
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
        verify(listOperations).remove(PROCESSING_QUEUE, 1, "10");
        verify(listOperations).remove(PROCESSING_QUEUE, 1, "20");
        verify(jobRepository, times(2)).save(highPriorityJob);
        verify(jobRepository, times(2)).save(mediumPriorityJob);
    }

    @Test
    void fetchFromPriorityQueuesEventuallyDequeuesLowPriorityEvenWhenHighHasJobs() {
        AtomicInteger counter = new AtomicInteger(0);
        QueueSelectionStrategy strategy = new WeightedQueueSelector(
                new String[]{HIGH_QUEUE, MEDIUM_QUEUE, LOW_QUEUE},
                new int[]{8, 1, 1},
                bound -> counter.getAndIncrement() % bound
        );
        ReflectionTestUtils.setField(workerService, "queueSelectionStrategy", strategy);
        ReflectionTestUtils.setField(workerService, "emptySelectionRetries", 0);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE)).thenReturn("101");
        when(listOperations.rightPopAndLeftPush(LOW_QUEUE, PROCESSING_QUEUE)).thenReturn("303");
        when(listOperations.rightPopAndLeftPush(MEDIUM_QUEUE, PROCESSING_QUEUE)).thenReturn(null);

        int lowDequeues = 0;
        for (int i = 0; i < 30; i++) {
            String jobId = workerService.fetchFromPriorityQueues();
            if ("303".equals(jobId)) {
                lowDequeues++;
            }
        }

        assertEquals(3, lowDequeues);
    }

    @Test
    void fetchFromPriorityQueuesFallsBackToScanWhenSelectedQueuesAreEmpty() {
        QueueSelectionStrategy alwaysHigh = fixedSelection(HIGH_QUEUE);
        ReflectionTestUtils.setField(workerService, "queueSelectionStrategy", alwaysHigh);
        ReflectionTestUtils.setField(workerService, "emptySelectionRetries", 2);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE)).thenReturn(null);
        when(listOperations.rightPopAndLeftPush(MEDIUM_QUEUE, PROCESSING_QUEUE)).thenReturn("202");
        when(listOperations.rightPopAndLeftPush(LOW_QUEUE, PROCESSING_QUEUE)).thenReturn(null);

        String dequeuedJobId = workerService.fetchFromPriorityQueues();

        assertEquals("202", dequeuedJobId);
        verify(listOperations, times(4)).rightPopAndLeftPush(HIGH_QUEUE, PROCESSING_QUEUE);
        verify(listOperations).rightPopAndLeftPush(MEDIUM_QUEUE, PROCESSING_QUEUE);
    }

    private QueueSelectionStrategy fixedSelection(String queueName) {
        return new QueueSelectionStrategy() {
            @Override
            public String selectQueue() {
                return queueName;
            }

            @Override
            public int queueCount() {
                return 3;
            }

            @Override
            public String queueAt(int index) {
                if (index == 0) {
                    return HIGH_QUEUE;
                }
                if (index == 1) {
                    return MEDIUM_QUEUE;
                }
                return LOW_QUEUE;
            }
        };
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
