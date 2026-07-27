package com.pratham.apiservice;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.enums.JobStatus;
import com.pratham.apiservice.repository.JobRepository;
import com.pratham.apiservice.service.JobService;
import com.pratham.apiservice.service.QueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiServiceApplicationTests {

	@Mock
	private JobRepository jobRepository;

	@Mock
	private QueueService queueService;

	@InjectMocks
	private JobService jobService;

	@Test
	void createJobSetsBaselineStateAndEnqueues() {
		Job request = new Job();
		request.setPriority(1);

		Job saved = new Job();
		when(jobRepository.save(request)).thenReturn(saved);

		Job result = jobService.createJob(request);

		assertSame(saved, result);
		assertEquals(JobStatus.QUEUED, request.getStatus());
		assertEquals(0, request.getRetryCount());
		assertNotNull(request.getCreatedAt());
		assertNotNull(request.getUpdatedAt());
		verify(queueService).enqueueJob(saved);
	}

}
