package com.example.bankingrisk.unit;

import com.example.bankingrisk.exception.RateLimitExceededException;
import com.example.bankingrisk.transaction.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    private RateLimiterService service;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new RateLimiterService(redis, 20);
    }

    @Test
    void firstRequest_isPermitted_andSetsTtl() {
        UUID userId = UUID.randomUUID();
        when(ops.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any())).thenReturn(true);

        assertThatCode(() -> service.checkRateLimit(userId)).doesNotThrowAnyException();
        verify(redis).expire(anyString(), any()); // TTL set on first request
    }

    @Test
    void requestAtLimit_isPermitted() {
        UUID userId = UUID.randomUUID();
        when(ops.increment(anyString())).thenReturn(20L); // exactly at limit

        assertThatCode(() -> service.checkRateLimit(userId)).doesNotThrowAnyException();
    }

    @Test
    void requestAboveLimit_throwsRateLimitExceeded() {
        UUID userId = UUID.randomUUID();
        when(ops.increment(anyString())).thenReturn(21L); // one over limit

        assertThatThrownBy(() -> service.checkRateLimit(userId))
            .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void differentUsers_haveIsolatedCounters() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(ops.increment("rate:transfer:" + user1)).thenReturn(25L); // over limit
        when(ops.increment("rate:transfer:" + user2)).thenReturn(1L);  // under limit
        when(redis.expire(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.checkRateLimit(user1))
            .isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> service.checkRateLimit(user2))
            .doesNotThrowAnyException();
    }

    @Test
    void redisReturnsNull_failsOpen_noException() {
        UUID userId = UUID.randomUUID();
        when(ops.increment(anyString())).thenReturn(null); // Redis failure

        assertThatCode(() -> service.checkRateLimit(userId)).doesNotThrowAnyException();
    }

    @Test
    void subsequentRequestsInSameWindow_doNotResetTtl() {
        UUID userId = UUID.randomUUID();
        when(ops.increment(anyString())).thenReturn(5L); // not first request

        service.checkRateLimit(userId);

        // TTL should only be set when count == 1
        verify(redis, never()).expire(anyString(), any());
    }
}
