package com.pratham.apiservice.service;

import com.pratham.apiservice.entity.Job;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ListOperations<String, Object> listOperations;

    @InjectMocks
    private QueueService queueService;

    @ParameterizedTest
    @CsvSource({
            "1,high_priority_queue",
            "2,medium_priority_queue",
            "3,low_priority_queue"
    })
    void enqueueJobPushesToExpectedQueue(int priority, String expectedQueue) {
        Job job = Job.builder()
                .id(101L)
                .priority(priority)
                .build();

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        queueService.enqueueJob(job);

        verify(listOperations).leftPush(expectedQueue, "101");
    }
}
