package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
@Import(
    {
        AiPracticeOutboxClaimService.class,
        AiPracticeOutboxResultService.class,
        AiPracticeEndCommandQueue.class
    }
)
class AiPracticeOutboxResultServiceIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiPracticeSessionRepository sessionRepository;

    @Autowired
    private AiPracticeChatRepository chatRepository;

    @Autowired
    private AiPracticeOutboxRepository outboxRepository;

    @Autowired
    private AiPracticeOutboxClaimService claimService;

    @Autowired
    private AiPracticeOutboxResultService resultService;

    private Long chatId;
    private Long outboxId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        chatRepository.deleteAll();
        sessionRepository.deleteAll();

        LocalDateTime createdAt = AiPracticeTime.now().minusMinutes(1);
        AiPracticeSession session = sessionRepository.save(
            new AiPracticeSession(USER_ID, TARGET_USER_ID, createdAt));
        AiPracticeChat chat = chatRepository.save(new AiPracticeChat(
            session,
            UUID.randomUUID(),
            "연습 메시지",
            AiPracticeTime.today(),
            createdAt));
        AiPracticeOutbox outbox = outboxRepository.save(new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE,
            session.getId(),
            chat.getId(),
            chat.getGenerationAttempt(),
            createdAt));
        chatId = chat.getId();
        outboxId = outbox.getId();
    }

    @Test
    void 처리_토큰이_일치할_때만_AI_답변을_저장한다() {
        AiPracticeOutbox claimed = claimService.tryClaim(outboxId).orElseThrow();

        assertThat(resultService.markGenerationCompleted(
            outboxId,
            UUID.randomUUID().toString(),
            "ai-session-42",
            "AI 답변")).isFalse();
        assertThat(chatRepository.findById(chatId).orElseThrow().getStatus())
            .isEqualTo(AiPracticeChatStatus.GENERATING);

        assertThat(resultService.markGenerationCompleted(
            outboxId,
            claimed.getProcessingToken(),
            "ai-session-42",
            "AI 답변")).isTrue();

        AiPracticeChat completed = chatRepository.findById(chatId).orElseThrow();
        AiPracticeOutbox published = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AiPracticeChatStatus.COMPLETED);
        assertThat(completed.getAiResponse()).isEqualTo("AI 답변");
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getProcessingToken()).isNull();
        assertThat(published.getProcessingUntil()).isNull();
    }

    @Test
    void 재시도를_예약하면_처리_토큰을_제거한다() {
        AiPracticeOutbox claimed = claimService.tryClaim(outboxId).orElseThrow();
        LocalDateTime nextAttemptAt = AiPracticeTime.now().plusMinutes(1);

        assertThat(resultService.recordFailure(
            outboxId,
            claimed.getProcessingToken(),
            "AI_SERVER_REQUEST_FAILED",
            10,
            nextAttemptAt)).isTrue();

        AiPracticeOutbox retry = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(retry.getFailureCount()).isEqualTo(1);
        assertThat(retry.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(retry.getProcessingToken()).isNull();
        assertThat(retry.getProcessingUntil()).isNull();
        assertThat(resultService.markSkipped(
            outboxId, claimed.getProcessingToken())).isFalse();
    }
}
