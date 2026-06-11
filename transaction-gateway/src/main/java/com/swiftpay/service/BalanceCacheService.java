package com.swiftpay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceCacheService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${balance-cache.ttl-seconds:60}")
    private long ttlSeconds;

    private static final String KEY_PREFIX = "balance:";

    public void cacheBalance(UUID accountId, BigDecimal balance) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + accountId,
                balance.toPlainString(),
                Duration.ofSeconds(ttlSeconds)
        );
    }

    public Optional<BigDecimal> getCachedBalance(UUID accountId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + accountId);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            log.warn("Invalid cached balance for account {}: {}", accountId, value);
            return Optional.empty();
        }
    }

    public void evictBalance(UUID accountId) {
        redisTemplate.delete(KEY_PREFIX + accountId);
    }
}
