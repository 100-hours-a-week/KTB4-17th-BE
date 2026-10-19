package com.team.dating_backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.aipractice.client.AiPracticeAiClient;
import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.aipractice.service.AiPracticeOutboxPublishJob;
import com.team.dating_backend.aipractice.service.AiPracticeOutboxResultService;
import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationRecoveryJob;
import com.team.dating_backend.chat.monitoring.ChatOutboxMetrics;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.service.ChatMessageOutboxPublishJob;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService;
import com.team.dating_backend.chat.service.ChatOutboxStartGate;
import com.team.dating_backend.chat.service.RabbitUserEventPublisher;
import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.service.FileUploadIntentCleanupJob;
import com.team.dating_backend.file.storage.FileStorage;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class WorkerActivationTest {

    private static final Map<String, Class<?>> WORKERS = Map.of(
        "chat-outbox-enabled", ChatMessageOutboxPublishJob.class,
        "ai-practice-enabled", AiPracticeOutboxPublishJob.class,
        "ai-simulation-recovery-enabled", AiSimulationRecoveryJob.class,
        "file-cleanup-enabled", FileUploadIntentCleanupJob.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ChatMessageOutboxPublishJob.class, AiPracticeOutboxPublishJob.class,
            AiSimulationRecoveryJob.class, FileUploadIntentCleanupJob.class)
        .withBean(ChatMessageOutboxRepository.class, () -> mock(ChatMessageOutboxRepository.class))
        .withBean(ChatMessageOutboxClaimService.class, () -> mock(ChatMessageOutboxClaimService.class))
        .withBean(ChatMessageRepository.class, () -> mock(ChatMessageRepository.class))
        .withBean(ChatParticipantRepository.class, () -> mock(ChatParticipantRepository.class))
        .withBean(RabbitUserEventPublisher.class, () -> mock(RabbitUserEventPublisher.class))
        .withBean(ChatOutboxMetrics.class, () -> mock(ChatOutboxMetrics.class))
        .withBean(ChatOutboxStartGate.class, () -> mock(ChatOutboxStartGate.class))
        .withBean(AiPracticeOutboxRepository.class, () -> mock(AiPracticeOutboxRepository.class))
        .withBean(AiPracticeSessionRepository.class, () -> mock(AiPracticeSessionRepository.class))
        .withBean(AiPracticeChatRepository.class, () -> mock(AiPracticeChatRepository.class))
        .withBean(AiPracticeAiClient.class, () -> mock(AiPracticeAiClient.class))
        .withBean(AiPracticeOutboxResultService.class, () -> mock(AiPracticeOutboxResultService.class))
        .withBean(AiPracticeProperties.class, AiPracticeProperties::new)
        .withBean(AiSimulationSessionRepository.class, () -> mock(AiSimulationSessionRepository.class))
        .withBean(AiSimulationPersistenceService.class, () -> mock(AiSimulationPersistenceService.class))
        .withBean(AiSimulationProperties.class, AiSimulationProperties::new)
        .withBean(FileStorage.class, () -> mock(FileStorage.class))
        .withBean(FileUploadIntentRepository.class, () -> mock(FileUploadIntentRepository.class))
        .withBean(FileProperties.class, FileProperties::new);

    @Test
    void workersRemainEnabledWhenOptionsAreAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WORKERS.values().forEach(type -> assertThat(context).hasSingleBean(type));
            assertThat(context).hasSingleBean(ApplicationRunner.class);
        });
    }

    @ParameterizedTest
    @MethodSource("workerOptions")
    void disablingOneWorkerLeavesOtherWorkersEnabled(String option, Class<?> disabledType) {
        contextRunner.withPropertyValues("app.workers." + option + "=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(disabledType);
            WORKERS.values().stream().filter(type -> type != disabledType)
                .forEach(type -> assertThat(context).hasSingleBean(type));
            if (disabledType == AiSimulationRecoveryJob.class) {
                assertThat(context).doesNotHaveBean(ApplicationRunner.class);
            }
        });
    }

    @Test
    void ecsEnvironmentVariablesDisableWorkersIncludingStartupRecovery() {
        Map<String, Object> environment = Map.of(
            "APP_WORKERS_CHAT_OUTBOX_ENABLED", "false",
            "APP_WORKERS_AI_PRACTICE_ENABLED", "false",
            "APP_WORKERS_AI_SIMULATION_RECOVERY_ENABLED", "false",
            "APP_WORKERS_FILE_CLEANUP_ENABLED", "false");

        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource("ecsEnvironment", environment)))
            .run(context -> {
                assertThat(context).hasNotFailed();
                WORKERS.values().forEach(type -> assertThat(context).doesNotHaveBean(type));
                assertThat(context).doesNotHaveBean(ApplicationRunner.class);
            });
    }

    private static Stream<Arguments> workerOptions() {
        return WORKERS.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }
}
