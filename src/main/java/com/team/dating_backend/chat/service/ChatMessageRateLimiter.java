package com.team.dating_backend.chat.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatMessageRateLimiter {

    private static final int BURST_CAPACITY = 10;
    private static final Duration REFILL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "chat:message-rate-limit:";

    private static final DefaultRedisScript<Long> TRY_ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
        local capacity = tonumber(ARGV[1])
        local refillIntervalMillis = tonumber(ARGV[2])
        local idleTtlMillis = tonumber(ARGV[3])
        local redisTime = redis.call('TIME')
        local nowMillis = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

        local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'lastRefillAt')
        local tokens = tonumber(bucket[1]) or capacity
        local lastRefillAt = tonumber(bucket[2]) or nowMillis
        local elapsedMillis = math.max(0, nowMillis - lastRefillAt)

        tokens = math.min(capacity, tokens + elapsedMillis / refillIntervalMillis)

        local permitted = 0
        if tokens >= 1 then
            tokens = tokens - 1
            permitted = 1
        end

        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'lastRefillAt', tostring(nowMillis))
        redis.call('PEXPIRE', KEYS[1], idleTtlMillis)
        return permitted
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Counter redisFailureCounter;
    private final int burstCapacity;
    private final long refillIntervalMillis;
    private final long idleTtlMillis;

    @Autowired
    public ChatMessageRateLimiter(
        StringRedisTemplate redisTemplate,
        MeterRegistry meterRegistry) {
        this(redisTemplate, meterRegistry, BURST_CAPACITY, REFILL_INTERVAL, IDLE_TTL);
    }

    ChatMessageRateLimiter(
        StringRedisTemplate redisTemplate,
        MeterRegistry meterRegistry,
        int burstCapacity,
        Duration refillInterval,
        Duration idleTtl) {
        this.redisTemplate = redisTemplate;
        this.redisFailureCounter = meterRegistry.counter(
            "chat.message.rate_limit.redis.failures");
        this.burstCapacity = burstCapacity;
        this.refillIntervalMillis = refillInterval.toMillis();
        this.idleTtlMillis = idleTtl.toMillis();
    }

    public boolean tryAcquire(Long userId) {
        Objects.requireNonNull(userId);

        try {
            Long result = redisTemplate.execute(
                TRY_ACQUIRE_SCRIPT,
                List.of(KEY_PREFIX + userId),
                Integer.toString(burstCapacity),
                Long.toString(refillIntervalMillis),
                Long.toString(idleTtlMillis));
            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException exception) {
            redisFailureCounter.increment();
            log.warn(
                "채팅 메시지 전송 제한을 확인할 수 없어 요청을 허용합니다. userId={}, exceptionType={}",
                userId,
                exception.getClass().getSimpleName());
            return true;
        }
    }
}
