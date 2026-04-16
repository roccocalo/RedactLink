package com.roccocalo.redactlink.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    private static final int MAX_UPLOADS_PER_HOUR = 5;

    public boolean isAllowed(String ip) {
        String key = "rate:" + ip;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        return count != null && count <= MAX_UPLOADS_PER_HOUR;
    }
}
