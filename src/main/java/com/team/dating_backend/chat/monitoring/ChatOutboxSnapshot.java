package com.team.dating_backend.chat.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지표 조회는 캐시만 읽고 DB 집계 쿼리를 실행하지 않는다.
 */
@Component
public class ChatOutboxSnapshot {

    static final String SNAPSHOT_SQL = """
        SELECT COUNT(CASE WHEN rabbit_failed_at IS NULL THEN 1 END) AS pending_count,
               COUNT(CASE WHEN rabbit_failed_at IS NOT NULL THEN 1 END) AS failed_count,
               MIN(CASE WHEN rabbit_failed_at IS NULL THEN created_at END) AS oldest_pending
        FROM chat_message_outbox
        WHERE rabbit_published_at IS NULL
        """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final int queryTimeoutSeconds;
    private volatile Snapshot snapshot;
    private volatile boolean lastCollectionSucceeded;
    private volatile long lastSuccess;

    public ChatOutboxSnapshot(
        JdbcTemplate jdbcTemplate,
        MeterRegistry registry,
        @Value("${app.chat.monitoring.outbox-snapshot-enabled:true}") boolean enabled,
        @Value("${app.chat.monitoring.query-timeout-seconds:3}") int queryTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
        Gauge.builder("chat.outbox.pending", this, ChatOutboxSnapshot::pending)
            .description("Unpublished non-terminal rows, including rows waiting for retry")
            .register(registry);
        Gauge.builder("chat.outbox.failed", this, ChatOutboxSnapshot::failed)
            .description("Retained terminal failures; includes historical failures")
            .register(registry);
        Gauge.builder("chat.outbox.oldest.pending.age", this, ChatOutboxSnapshot::oldestAge)
            .baseUnit("seconds")
            .description("Age since creation, not next retry time")
            .register(registry);
        Gauge.builder("chat.outbox.snapshot.success", this,
            value -> value.lastCollectionSucceeded ? 1 : 0).register(registry);
        Gauge.builder("chat.outbox.snapshot.last.success.unixtime", this,
            value -> value.lastSuccess).register(registry);
        Gauge.builder("chat.outbox.snapshot.enabled", this,
            value -> value.enabled ? 1 : 0).register(registry);
    }

    @Scheduled(
        scheduler = "chatMonitoringScheduler",
        fixedDelayString = "${app.chat.monitoring.snapshot-delay-ms:10000}"
    )
    public void collect() {
        if (!enabled) {
            return;
        }
        try {
            Snapshot next = jdbcTemplate.query(SNAPSHOT_SQL,
                statement -> statement.setQueryTimeout(queryTimeoutSeconds),
                result -> {
                    if (!result.next()) {
                        throw new IllegalStateException("Outbox aggregate returned no row");
                    }
                    Timestamp oldest = result.getTimestamp("oldest_pending");
                    return new Snapshot(result.getLong("pending_count"),
                        result.getLong("failed_count"),
                        oldest == null ? null : oldest.toLocalDateTime());
                });
            snapshot = next;
            lastSuccess = Instant.now().getEpochSecond();
            lastCollectionSucceeded = true;
        } catch (DataAccessException exception) {
            // 조회 실패를 빈 Outbox로 판단하지 않고 이전 값을 유지한다.
            lastCollectionSucceeded = false;
        }
    }

    private double pending() {
        Snapshot current = snapshot;
        return current == null ? Double.NaN : current.pending();
    }

    private double failed() {
        Snapshot current = snapshot;
        return current == null ? Double.NaN : current.failed();
    }

    private double oldestAge() {
        Snapshot current = snapshot;
        if (current == null) {
            return Double.NaN;
        }
        if (current.oldest() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(current.oldest(), LocalDateTime.now()).toMillis() / 1000.0);
    }

    private record Snapshot(long pending, long failed, LocalDateTime oldest) {}
}
