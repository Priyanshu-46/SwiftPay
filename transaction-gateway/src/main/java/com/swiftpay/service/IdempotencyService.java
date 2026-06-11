package com.swiftpay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${idempotency.ttl-seconds:86400}")
    private long ttlSeconds;

    private static final String KEY_PREFIX = "idem:";

    /**
     * Attempts to reserve an idempotency key atomically (SET NX EX).
     * Returns true if this is the first time the key is seen (new request).
     * Returns false if the key already exists (duplicate request).
     */
    public boolean tryReserve(String idempotencyKey, String paymentId) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, paymentId, Duration.ofSeconds(ttlSeconds));
        log.debug("Idempotency check key={} isNew={}", redisKey, isNew);
        return Boolean.TRUE.equals(isNew);
    }

    /**
     * Returns the stored payment ID for an existing idempotency key, if present.
     */
    public Optional<String> getExistingPaymentId(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return Optional.ofNullable(value);
    }

    /**
     * Explicitly removes an idempotency key (e.g., on rollback).
     */
    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}
