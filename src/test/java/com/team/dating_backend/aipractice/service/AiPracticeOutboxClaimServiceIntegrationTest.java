package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.ai-practice.outbox-claim-duration-ms=30000"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(AiPracticeProperties.class)
@Import(AiPracticeOutboxClaimService.class)
class AiPracticeOutboxClaimServiceIntegrationTest {

    private static final Long SESSION_ID = 10L;
    private static final Long CHAT_ID = 20L;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiPracticeOutboxRepository outboxRepository;

    @Autowired
    private AiPracticeOutboxClaimService claimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long outboxId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        LocalDateTime createdAt = AiPracticeTime.now().minusMinutes(1);
        outboxId = outboxRepository.save(new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE,
            SESSION_ID,
            CHAT_ID,
            1,
            createdAt)).getId();
    }

    @Test
    void 같은_AI_연습_요청은_한_서버만_수행한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claimAfter(start));
            var second = executor.submit(() -> claimAfter(start));
            start.countDown();

            assertThat(List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(false, true);
        }

        AiPracticeOutbox claimed = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(claimed.getProcessingToken()).isNotBlank();
        assertThat(claimed.getProcessingUntil()).isAfter(databaseNow());
    }

    @Test
    void 처리_중인_Outbox의_만료_시간을_연장한다() {
        AiPracticeOutbox claimed = claimService.tryClaim(outboxId).orElseThrow();
        LocalDateTime beforeRenewal = claimed.getProcessingUntil();

        assertThat(claimService.renew(outboxId, claimed.getProcessingToken())).isTrue();

        AiPracticeOutbox renewed = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(renewed.getProcessingToken()).isEqualTo(claimed.getProcessingToken());
        assertThat(renewed.getProcessingUntil()).isAfterOrEqualTo(beforeRenewal);
    }

    @Test
    void 처리_시간이_만료되면_다른_서버가_이어받을_수_있다() {
        AiPracticeOutbox first = claimService.tryClaim(outboxId).orElseThrow();
        assertThat(outboxRepository.findAvailable(
            AiPracticeTime.now(), PageRequest.of(0, 10))).isEmpty();
        expireClaim();

        AiPracticeOutbox second = claimService.tryClaim(outboxId).orElseThrow();

        assertThat(second.getProcessingToken()).isNotEqualTo(first.getProcessingToken());
        assertThat(second.getProcessingUntil()).isAfter(databaseNow());
    }

    private boolean claimAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        return claimService.tryClaim(outboxId).isPresent();
    }

    private void expireClaim() {
        jdbcTemplate.update(
            """
                UPDATE ai_practice_outbox
                SET processing_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
                WHERE id = ?
                """,
            outboxId);
    }

    private LocalDateTime databaseNow() {
        return jdbcTemplate.queryForObject(
            "SELECT UTC_TIMESTAMP(6)",
            (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
    }
}
