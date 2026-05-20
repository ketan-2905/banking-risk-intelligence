package com.example.bankingrisk.transaction.ratelimit;

import com.example.bankingrisk.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class RateLimiterService {

    private static final String KEY_FMT = "rate:transfer:%s";

    private final StringRedisTemplate redis;
    private final int requestsPerMinute;

    public RateLimiterService(
        StringRedisTemplate redis,
        @Value("${app.transfer.rate-limit.requests-per-minute:20}") int requestsPerMinute) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
    }

    public void checkRateLimit(UUID userId) {
        String key = KEY_FMT.formatted(userId);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return; // Redis failure — fail open rather than blocking all traffic
        }
        if (count == 1L) {
            // First request in this window — set TTL
            redis.expire(key, Duration.ofMinutes(1));
        }
        if (count > requestsPerMinute) {
            throw new RateLimitExceededException(
                "Rate limit exceeded: max %d requests/minute".formatted(requestsPerMinute));
        }
    }
}
