package com.pratham.apiservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pratham.apiservice.queue.QueueKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes lifecycle events on the same channel the workers use, so the dashboard sees one stream. */
@Component
public class EventPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public EventPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void publish(String type, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("ts", System.currentTimeMillis());
        event.putAll(fields);
        try {
            redis.convertAndSend(QueueKeys.EVENTS_CHANNEL, mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
