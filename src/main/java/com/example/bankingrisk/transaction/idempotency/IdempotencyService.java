package com.example.bankingrisk.transaction.idempotency;

import com.example.bankingrisk.exception.RequestInProgressException;
import com.example.bankingrisk.observability.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final String LOCK_KEY_FMT = "idem:transfer:%s:%s:lock";
    private static final String RESP_KEY_FMT = "idem:transfer:%s:%s:response";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long lockTtlSeconds;
    private final long lockWaitMs;
    private final long responseTtlSeconds;
    private final MetricsService metricsService;

    public IdempotencyService(
        StringRedisTemplate redis,
        ObjectMapper mapper,
        @Value("${app.transfer.idempotency.lock-ttl-seconds:30}") long lockTtlSeconds,
        @Value("${app.transfer.idempotency.lock-wait-ms:200}") long lockWaitMs,
        @Value("${app.transfer.idempotency.response-ttl-seconds:86400}") long responseTtlSeconds,
        MetricsService metricsService) {
        this.redis = redis;
        this.mapper = mapper;
        this.lockTtlSeconds = lockTtlSeconds;
        this.lockWaitMs = lockWaitMs;
        this.responseTtlSeconds = responseTtlSeconds;
        this.metricsService = metricsService;
    }

    public <T> T execute(UUID userId, String idempotencyKey, Class<T> responseType, Supplier<T> action) {
        String lockKey = LOCK_KEY_FMT.formatted(userId, idempotencyKey);
        String respKey = RESP_KEY_FMT.formatted(userId, idempotencyKey);

        // Fast path: return cached response if available
        String cached = redis.opsForValue().get(respKey);
        if (cached != null) {
            metricsService.recordIdempotencyCacheHit();
            return deserialize(cached, responseType);
        }
        metricsService.recordIdempotencyCacheMiss();

        // Acquire distributed lock (SET NX PX)
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(locked)) {
            // Another request holds the lock — wait briefly and re-check cache
            if (lockWaitMs > 0) {
                try { Thread.sleep(lockWaitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            String retry = redis.opsForValue().get(respKey);
            if (retry != null) {
                metricsService.recordIdempotencyCacheHit();
                return deserialize(retry, responseType);
            }
            throw new RequestInProgressException("Request in progress for idempotency key: " + idempotencyKey);
        }

        try {
            T result = action.get();
            // Cache successful response before releasing lock
            redis.opsForValue().set(respKey, serialize(result), Duration.ofSeconds(responseTtlSeconds));
            return result;
        } finally {
            redis.delete(lockKey);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached idempotency response", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize idempotency response", e);
        }
    }
}
