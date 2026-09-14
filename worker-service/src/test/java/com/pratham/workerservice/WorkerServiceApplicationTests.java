package com.pratham.workerservice;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import com.pratham.workerservice.repository.JobRepository;
import com.pratham.workerservice.service.QueueSelectionStrategy;
import com.pratham.workerservice.service.WorkerService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceApplicationTests {

	@Mock
	private RedisTemplate<String, String> redisTemplate;

	@Mock
	private ListOperations<String, String> listOperations;

	@Mock
	private JobRepository jobRepository;

	@InjectMocks
	private WorkerService workerService;

	private static final String HIGH_QUEUE = "high_priority_queue";
	private static final String MEDIUM_QUEUE = "medium_priority_queue";
	private static final String LOW_QUEUE = "low_priority_queue";

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(workerService, "queueSelectionStrategy", fixedSelection(HIGH_QUEUE));
		ReflectionTestUtils.setField(workerService, "emptySelectionRetries", 0);
	}

	private Job buildJob(long id, JobStatus status, int retryCount, int maxRetries) {
		Job job = new Job();
		job.setId(id);
		job.setStatus(status);
		job.setPriority(1);
		job.setRetryCount(retryCount);
		job.setMaxRetries(maxRetries);
		return job;
	}

	@Test
	void processJobsMarksQueuedJobSuccessful() {
		Job job = buildJob(1L, JobStatus.QUEUED, 0, 3);

		when(redisTemplate.opsForList()).thenReturn(listOperations);
		when(listOperations.rightPopAndLeftPush(HIGH_QUEUE, "processing_queue")).thenReturn("1");
		when(jobRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
		when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

		workerService.processJobs();

		assertEquals(JobStatus.SUCCESS, job.getStatus());
		verify(jobRepository, times(2)).save(job);
		verify(listOperations).remove("processing_queue", 1, "1");
	}

	@Test
	void handleJobFailureMovesJobToRetryingWhenRetriesRemain() {
		Job job = buildJob(10L, JobStatus.PROCESSING, 0, 3);

		when(redisTemplate.opsForList()).thenReturn(listOperations);

		ReflectionTestUtils.invokeMethod(workerService, "handleJobFailure", job, "10");

		assertEquals(JobStatus.RETRYING, job.getStatus());
		assertEquals(1, job.getRetryCount());
		assertNotNull(job.getNextRetryAt());
		verify(jobRepository).save(job);
		verify(listOperations, never()).leftPush("dead_letter_queue", "10");
		verify(listOperations).remove("processing_queue", 1, "10");
	}

	@Test
	void handleJobFailureMovesJobToDlqWhenRetriesExhausted() {
		Job job = buildJob(11L, JobStatus.PROCESSING, 3, 3);

		when(redisTemplate.opsForList()).thenReturn(listOperations);

		ReflectionTestUtils.invokeMethod(workerService, "handleJobFailure", job, "11");

		assertEquals(JobStatus.DLQ, job.getStatus());
		assertEquals(3, job.getRetryCount());
		verify(jobRepository).save(job);
		verify(listOperations).leftPush("dead_letter_queue", "11");
		verify(listOperations).remove("processing_queue", 1, "11");
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
}
