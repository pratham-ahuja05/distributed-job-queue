package com.pratham.workerservice.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DLQService {

    private static final String DEAD_LETTER_QUEUE = "dead_letter_queue";

    private final RedisTemplate<String, String> redisTemplate;

    public DLQService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelay = 10000)
    public void processDLQ() {

        String jobId = redisTemplate.opsForList().rightPop(DEAD_LETTER_QUEUE);

        if (jobId != null) {
            System.out.println("DLQ job found: " + jobId);

            // future: alert / log / manual retry
        }
    }
}