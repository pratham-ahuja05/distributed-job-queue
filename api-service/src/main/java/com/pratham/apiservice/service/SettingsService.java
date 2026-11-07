package com.pratham.apiservice.service;

import com.pratham.apiservice.queue.QueueKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Runtime knobs shared with the workers through Redis. */
@Service
public class SettingsService {

    private final StringRedisTemplate redis;

    public SettingsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public double failureRate() {
        String raw = redis.opsForValue().get(QueueKeys.FAILURE_RATE_KEY);
        if (raw == null) return 0.0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public double setFailureRate(double rate) {
        double clamped = Math.max(0.0, Math.min(0.9, rate));
        redis.opsForValue().set(QueueKeys.FAILURE_RATE_KEY, Double.toString(clamped));
        return clamped;
    }
}
