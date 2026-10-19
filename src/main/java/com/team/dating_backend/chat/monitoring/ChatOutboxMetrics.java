package com.team.dating_backend.chat.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 브라우저 수신 여부가 아닌 발행 작업을 기록한다. */
@Component
public class ChatOutboxMetrics {

    private final Counter completed;
    private final Counter retried;
    private final Counter failed;
    private final Counter databaseErrors;
    private final Timer publishDelay;
    private final AtomicLong lastSuccessfulPoll = new AtomicLong();

    public ChatOutboxMetrics(MeterRegistry registry) {
        completed = registry.counter("chat.outbox.results", "outcome", "completed");
        retried = registry.counter("chat.outbox.results", "outcome", "retry");
        failed = registry.counter("chat.outbox.results", "outcome", "failed");
        databaseErrors = registry.counter("chat.outbox.database.errors");
        publishDelay = Timer.builder("chat.outbox.publish.delay")
            .description("Outbox creation to publisher completion; NOT client delivery latency")
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(30))
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(registry);
        Gauge.builder("chat.outbox.poll.last.success.unixtime", lastSuccessfulPoll, AtomicLong::get)
            .description("Unix seconds of last completed outbox poll; zero means never completed")
            .register(registry);
    }

    public void completed(LocalDateTime createdAt, LocalDateTime publishedAt) {
        completed.increment();
        Duration delay = Duration.between(createdAt, publishedAt);
        publishDelay.record(delay.isNegative() ? Duration.ZERO : delay);
    }

    public void retried() {
        retried.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void databaseError() {
        databaseErrors.increment();
    }

    public void pollSucceeded() {
        lastSuccessfulPoll.set(Instant.now().getEpochSecond());
    }
}
