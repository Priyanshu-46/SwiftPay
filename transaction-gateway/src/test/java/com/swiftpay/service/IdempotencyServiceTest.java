package com.swiftpay.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService unit tests")
class IdempotencyServiceTest {

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    @InjectMocks
    IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(idempotencyService, "ttlSeconds", 86400L);
    }

    @Test
    @DisplayName("tryReserve — returns true for a new key")
    void tryReserve_newKey_returnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        boolean result =
                idempotencyService.tryReserve("key-abc", UUID.randomUUID().toString());

        assertThat(result).isTrue();

        verify(valueOps).setIfAbsent(
                eq("idem:key-abc"),
                anyString(),
                eq(Duration.ofSeconds(86400))
        );
    }

    @Test
    @DisplayName("tryReserve — returns false for an existing key")
    void tryReserve_existingKey_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean result =
                idempotencyService.tryReserve("key-abc", UUID.randomUUID().toString());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryReserve — treats null Redis response as false")
    void tryReserve_nullRedisResponse_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);

        boolean result =
                idempotencyService.tryReserve("key-null", "some-id");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("getExistingPaymentId — returns value when key exists")
    void getExistingPaymentId_keyExists_returnsValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String paymentId = UUID.randomUUID().toString();
        when(valueOps.get("idem:key-xyz")).thenReturn(paymentId);

        Optional<String> result =
                idempotencyService.getExistingPaymentId("key-xyz");

        assertThat(result).isPresent().contains(paymentId);
    }

    @Test
    @DisplayName("getExistingPaymentId — returns empty when key missing")
    void getExistingPaymentId_keyMissing_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<String> result =
                idempotencyService.getExistingPaymentId("missing-key");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("release — deletes the Redis key")
    void release_deletesKey() {
        idempotencyService.release("key-to-delete");

        verify(redisTemplate).delete("idem:key-to-delete");
        verifyNoInteractions(valueOps);
    }
}