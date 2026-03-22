package com.pratham.apiservice.service;
import com.pratham.apiservice.entity.Job;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private final RedisTemplate<String, Object> redisTemplate;

    public QueueService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void enqueueJob(Job job) {

        String queueName;

        if (job.getPriority() == 1) {
            queueName = "high_priority_queue";
        } else if (job.getPriority() == 2) {
            queueName = "medium_priority_queue";
        } else {
            queueName = "low_priority_queue";
        }

        redisTemplate.opsForList()
                .leftPush(queueName, job.getId().toString());
    }
}