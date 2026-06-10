package com.example.bankingrisk.unit;

import com.example.bankingrisk.exception.RequestInProgressException;
import com.example.bankingrisk.observability.MetricsService;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.idempotency.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;
    @Mock MetricsService metricsService;

    private IdempotencyService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final String idemKey = "test-key-123";

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(ops);
        // lock-wait-ms=0 so tests don't sleep
        service = new IdempotencyService(redis, mapper, 30L, 0L, 86400L, metricsService);
    }

    @Test
    void firstRequest_executesSupplier_andCachesResponse() {
        CreateTransferResponse expected =
            new CreateTransferResponse(UUID.randomUUID(), "POSTED", 0, "LOW", "ok");

        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);

        AtomicInteger callCount = new AtomicInteger();
        CreateTransferResponse result = service.execute(userId, idemKey, CreateTransferResponse.class, () -> {
            callCount.incrementAndGet();
            return expected;
        });

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(result.transferId()).isEqualTo(expected.transferId());
        assertThat(result.status()).isEqualTo("POSTED");
        verify(ops).set(anyString(), anyString(), any());
        verify(redis).delete(anyString());
    }

    @Test
    void retryRequest_returnsCachedResponse_supplierNotCalled() throws Exception {
        CreateTransferResponse cached =
            new CreateTransferResponse(UUID.randomUUID(), "POSTED", 0, "LOW", "ok");
        String json = mapper.writeValueAsString(cached);

        when(ops.get(anyString())).thenReturn(json);

        Supplier<CreateTransferResponse> supplier = mock();
        CreateTransferResponse result = service.execute(userId, idemKey, CreateTransferResponse.class, supplier);

        assertThat(result.transferId()).isEqualTo(cached.transferId());
        verify(supplier, never()).get();
        verify(ops, never()).setIfAbsent(anyString(), any(), any());
    }

    @Test
    void concurrentRequest_lockNotAcquired_cacheStillEmpty_throwsRequestInProgress() {
        // No cached response on either check
        when(ops.get(anyString())).thenReturn(null).thenReturn(null);
        // Lock not acquired (another request holds it)
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(false);

        Supplier<CreateTransferResponse> supplier = mock();

        assertThatThrownBy(() ->
            service.execute(userId, idemKey, CreateTransferResponse.class, supplier))
            .isInstanceOf(RequestInProgressException.class);

        verify(supplier, never()).get();
    }

    @Test
    void concurrentRequest_lockNotAcquired_cachePopulatedWhileWaiting_returnsCached() throws Exception {
        CreateTransferResponse cached =
            new CreateTransferResponse(UUID.randomUUID(), "POSTED", 0, "LOW", "ok");
        String json = mapper.writeValueAsString(cached);

        // First get: no cache. Second get (after brief wait): cache present
        when(ops.get(anyString())).thenReturn(null, json);
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(false);

        Supplier<CreateTransferResponse> supplier = mock();
        CreateTransferResponse result = service.execute(userId, idemKey, CreateTransferResponse.class, supplier);

        assertThat(result.transferId()).isEqualTo(cached.transferId());
        verify(supplier, never()).get();
    }

    @Test
    void lockReleasedInFinallyBlock_evenOnActionException() {
        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);

        assertThatThrownBy(() ->
            service.execute(userId, idemKey, CreateTransferResponse.class, () -> {
                throw new RuntimeException("transfer failed");
            }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("transfer failed");

        // Lock must be released even on failure
        verify(redis).delete(anyString());
        // Response must NOT be cached on failure
        verify(ops, never()).set(anyString(), anyString(), any());
    }
}
