package com.team.dating_backend.chat.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageOutboxClaimService {

    // 발행·재시도 시각은 기존 LocalDateTime 규칙을 유지한다.
    // 선점 만료만 공통 DB 시각을 사용해 작업자별 시간대 차이를 피한다.

    private static final int MAX_BATCH_SIZE = 100;
    private static final long MIN_LEASE_MS = 3_000;
    private static final long MAX_LEASE_MS = Long.MAX_VALUE / 1_000_000;
    // 재선점된 행을 이전 작업자가 완료·실패 처리하지 못하도록 소유권을 확인한다.
    private static final String OWNED_PENDING = """
        WHERE id = :id AND rabbit_claim_token = :token
            AND rabbit_published_at IS NULL AND rabbit_failed_at IS NULL
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final long leaseMicros;
    private final long leaseNanos;

    public ChatMessageOutboxClaimService(JdbcTemplate jdbc,
        @Value("${app.chat.outbox.claim-lease-ms:30000}") long leaseMs) {
        if (leaseMs < MIN_LEASE_MS || leaseMs > MAX_LEASE_MS) {
            throw new IllegalArgumentException("Outbox claim lease must be between "
                + MIN_LEASE_MS + " and " + MAX_LEASE_MS + " milliseconds");
        }
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.leaseMicros = leaseMs * 1_000;
        this.leaseNanos = leaseMs * 1_000_000;
    }

    public long leaseNanos() {
        return leaseNanos;
    }

    public long renewalNanos() {
        return leaseNanos / 3;
    }

    // 브로커 응답을 기다리는 동안 DB 잠금을 잡지 않도록 선점부터 커밋한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ClaimedBatch claimBatch(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Outbox claim batch size must be positive");
        }
        // 다른 작업자가 잠근 행은 건너뛰어 EC2와 ECS가 발행 대상을 나눠 맡는다.
        List<ClaimedOutbox> items = jdbc.query("""
            SELECT id, chat_message_id, created_at, rabbit_failure_count
            FROM chat_message_outbox
            WHERE rabbit_published_at IS NULL AND rabbit_failed_at IS NULL
                AND rabbit_next_attempt_at <= :dueNow
                AND (rabbit_claim_until IS NULL OR rabbit_claim_until <= CURRENT_TIMESTAMP(6))
            ORDER BY rabbit_next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, new MapSqlParameterSource("limit", Math.min(maxBatchSize, MAX_BATCH_SIZE))
            .addValue("dueNow", LocalDateTime.now()),
            (row, rowNumber) -> new ClaimedOutbox(row.getLong("id"), row.getLong("chat_message_id"),
                row.getTimestamp("created_at").toLocalDateTime(), row.getInt("rabbit_failure_count")));
        if (items.isEmpty()) {
            return new ClaimedBatch(null, List.of());
        }

        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_claim_token = :token,
                rabbit_claim_until = TIMESTAMPADD(MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6))
            WHERE id IN (:ids)
            """, new MapSqlParameterSource("token", token)
            .addValue("leaseMicros", leaseMicros)
            .addValue("ids", items.stream().map(ClaimedOutbox::id).toList()));
        if (updated != items.size()) {
            throw new IllegalStateException("Outbox claim did not update every locked row");
        }
        return new ClaimedBatch(token, items);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int renew(String token, Collection<Long> pendingIds) {
        if (pendingIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_claim_until = TIMESTAMPADD(MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6))
            WHERE id IN (:ids) AND rabbit_claim_token = :token
                AND rabbit_published_at IS NULL AND rabbit_failed_at IS NULL
                AND rabbit_claim_until > CURRENT_TIMESTAMP(6)
            """, new MapSqlParameterSource("token", token)
            .addValue("ids", List.copyOf(pendingIds))
            .addValue("leaseMicros", leaseMicros));
    }

    // 늦은 발행 확인은 다른 작업자가 재선점하기 전까지만 반영한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean complete(long id, String token) {
        return jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_published_at = :publishedAt, rabbit_last_failure_type = NULL,
                rabbit_claim_token = NULL, rabbit_claim_until = NULL
            """ + OWNED_PENDING,
            ownedParameters(id, token).addValue("publishedAt", LocalDateTime.now())) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean retry(long id, String token, long delaySeconds, String failureType) {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("Outbox retry delay must not be negative");
        }
        return jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_failure_count = rabbit_failure_count + 1,
                rabbit_next_attempt_at = :nextAttemptAt,
                rabbit_last_failure_type = :failureType,
                rabbit_claim_token = NULL, rabbit_claim_until = NULL
            """ + OWNED_PENDING, ownedParameters(id, token)
            .addValue("nextAttemptAt", LocalDateTime.now().plusSeconds(delaySeconds))
            .addValue("failureType", failureType)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean fail(long id, String token, String failureType) {
        return jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_failure_count = rabbit_failure_count + 1,
                rabbit_failed_at = :failedAt, rabbit_last_failure_type = :failureType,
                rabbit_claim_token = NULL, rabbit_claim_until = NULL
            """ + OWNED_PENDING, ownedParameters(id, token)
            .addValue("failedAt", LocalDateTime.now()).addValue("failureType", failureType)) == 1;
    }

    private MapSqlParameterSource ownedParameters(long id, String token) {
        return new MapSqlParameterSource("id", id).addValue("token", token);
    }

    public record ClaimedOutbox(long id, long chatMessageId, LocalDateTime createdAt,
        int rabbitFailureCount) {}

    public record ClaimedBatch(String token, List<ClaimedOutbox> items) {

        public ClaimedBatch {
            items = List.copyOf(items);
        }
    }
}
