package com.team.dating_backend.chat.service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatMessageRateLimiter {

    private static final int BURST_CAPACITY = 5;
    private static final long REFILL_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long IDLE_TTL_NANOS = Duration.ofMinutes(10).toNanos();

    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId) {
        Objects.requireNonNull(userId);

        long now = System.nanoTime();
        boolean[] permitted = new boolean[1];
        buckets.compute(userId, (id, existing) -> {
            Bucket bucket = existing == null ? new Bucket(now) : existing;
            permitted[0] = bucket.tryAcquire(now);
            return bucket;
        });
        return permitted[0];
    }

    @Scheduled(fixedDelay = 600_000)
    public void evictIdleBuckets() {
        long now = System.nanoTime();
        for (Long userId : buckets.keySet()) {
            buckets.computeIfPresent(userId,
                (id, bucket) -> now - bucket.lastAccessNanos >= IDLE_TTL_NANOS ? null : bucket);
        }
    }

    private static final class Bucket {

        private double tokens = BURST_CAPACITY;
        private long lastRefillNanos;
        private long lastAccessNanos;

        private Bucket(long now) {
            this.lastRefillNanos = now;
            this.lastAccessNanos = now;
        }

        private boolean tryAcquire(long now) {
            long elapsed = Math.max(0, now - lastRefillNanos);
            tokens = Math.min(BURST_CAPACITY,
                tokens + (double) elapsed / REFILL_INTERVAL_NANOS);
            lastRefillNanos = now;
            lastAccessNanos = now;

            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
