package com.team.dating_backend.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.aipractice.client.AiPracticeAiClient;
import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.controller.AiPracticeGenerationCallbackController;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.aipractice.service.AiPracticeCallbackSecretVerifier;
import com.team.dating_backend.aipractice.service.AiPracticeGenerationCallbackService;
import com.team.dating_backend.aipractice.service.AiPracticeOutboxPublishJob;
import com.team.dating_backend.aipractice.service.AiPracticeOutboxResultService;
import com.team.dating_backend.aipractice.service.AiPracticeWebSocketEventPublisher;
import com.team.dating_backend.chat.delegation.controller.ChatDelegationController;
import com.team.dating_backend.chat.controller.ChatMessageImageAccessController;
import com.team.dating_backend.chat.controller.ChatMessageListController;
import com.team.dating_backend.chat.controller.ChatMessageReadController;
import com.team.dating_backend.chat.controller.ChatMessageSendController;
import com.team.dating_backend.chat.controller.ChatRoomLeaveController;
import com.team.dating_backend.chat.controller.ChatRoomListController;
import com.team.dating_backend.chat.controller.ChatRoomPresenceController;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.delegation.service.ChatDelegationDraftService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationStartService;
import com.team.dating_backend.chat.service.ChatMessageImageAccessService;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.chat.service.ChatMessageOutboxPublishJob;
import com.team.dating_backend.chat.service.ChatMessageReadReceiptPublisher;
import com.team.dating_backend.chat.service.ChatMessageReadService;
import com.team.dating_backend.chat.service.ChatMessageSendService;
import com.team.dating_backend.chat.service.ChatRoomLeaveService;
import com.team.dating_backend.chat.service.ChatRoomListService;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.chat.service.ChatRoomPresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ChatRuntimeConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ChatRuntimeComponentsConfiguration.class)
        .withBean(ChatDelegationDraftService.class, () -> mock(ChatDelegationDraftService.class))
        .withBean(ChatDelegationStartService.class, () -> mock(ChatDelegationStartService.class))
        .withBean(ChatMessageImageAccessService.class,
            () -> mock(ChatMessageImageAccessService.class))
        .withBean(ChatMessageListService.class, () -> mock(ChatMessageListService.class))
        .withBean(ChatMessageReadService.class, () -> mock(ChatMessageReadService.class))
        .withBean(ChatMessageSendService.class, () -> mock(ChatMessageSendService.class))
        .withBean(ChatRoomLeaveService.class, () -> mock(ChatRoomLeaveService.class))
        .withBean(ChatRoomListService.class, () -> mock(ChatRoomListService.class))
        .withBean(ChatRoomParticipantDisplayService.class,
            () -> mock(ChatRoomParticipantDisplayService.class))
        .withBean(ChatRoomPresenceService.class, () -> mock(ChatRoomPresenceService.class))
        .withBean(ChatMessageOutboxRepository.class,
            () -> mock(ChatMessageOutboxRepository.class))
        .withBean(ChatMessageRepository.class, () -> mock(ChatMessageRepository.class))
        .withBean(ChatParticipantRepository.class,
            () -> mock(ChatParticipantRepository.class))
        .withBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class))
        .withBean(AiPracticeOutboxRepository.class,
            () -> mock(AiPracticeOutboxRepository.class))
        .withBean(AiPracticeSessionRepository.class,
            () -> mock(AiPracticeSessionRepository.class))
        .withBean(AiPracticeChatRepository.class,
            () -> mock(AiPracticeChatRepository.class))
        .withBean(AiPracticeAiClient.class, () -> mock(AiPracticeAiClient.class))
        .withBean(AiPracticeOutboxResultService.class,
            () -> mock(AiPracticeOutboxResultService.class))
        .withBean(AiPracticeProperties.class, () -> mock(AiPracticeProperties.class))
        .withBean(AiPracticeCallbackSecretVerifier.class,
            () -> mock(AiPracticeCallbackSecretVerifier.class))
        .withBean(AiPracticeGenerationCallbackService.class,
            () -> mock(AiPracticeGenerationCallbackService.class));

    @Test
    void 런타임이_비활성화되면_채팅_실행_컴포넌트를_등록하지_않는다() {
        new ApplicationContextRunner()
            .withUserConfiguration(AllChatRuntimeConfiguration.class)
            .withPropertyValues("app.chat.runtime-enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ChatWebSocketConfig.class);
                assertRuntimeComponentsAreAbsent(context);
            });
    }

    @Test
    void 런타임이_활성화되면_채팅_실행_컴포넌트를_등록한다() {
        contextRunner
            .withPropertyValues("app.chat.runtime-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ChatDelegationController.class);
                assertThat(context).hasSingleBean(ChatMessageImageAccessController.class);
                assertThat(context).hasSingleBean(ChatMessageListController.class);
                assertThat(context).hasSingleBean(ChatMessageReadController.class);
                assertThat(context).hasSingleBean(ChatMessageSendController.class);
                assertThat(context).hasSingleBean(ChatRoomLeaveController.class);
                assertThat(context).hasSingleBean(ChatRoomListController.class);
                assertThat(context).hasSingleBean(ChatRoomPresenceController.class);
                assertThat(context).hasSingleBean(ChatMessageOutboxPublishJob.class);
                assertThat(context).hasSingleBean(ChatMessageReadReceiptPublisher.class);
                assertThat(context).hasSingleBean(AiPracticeOutboxPublishJob.class);
                assertThat(context).hasSingleBean(AiPracticeWebSocketEventPublisher.class);
                assertThat(context).hasSingleBean(AiPracticeGenerationCallbackController.class);
            });
    }

    private void assertRuntimeComponentsAreAbsent(ApplicationContext context) {
        assertThat(context.getBeansOfType(ChatDelegationController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageImageAccessController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageListController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageReadController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageSendController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatRoomLeaveController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatRoomListController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatRoomPresenceController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageOutboxPublishJob.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageReadReceiptPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(AiPracticeOutboxPublishJob.class)).isEmpty();
        assertThat(context.getBeansOfType(AiPracticeWebSocketEventPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(AiPracticeGenerationCallbackController.class)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(
        {
            ChatDelegationController.class,
            ChatMessageImageAccessController.class,
            ChatMessageListController.class,
            ChatMessageReadController.class,
            ChatMessageSendController.class,
            ChatRoomLeaveController.class,
            ChatRoomListController.class,
            ChatRoomPresenceController.class,
            ChatMessageOutboxPublishJob.class,
            ChatMessageReadReceiptPublisher.class,
            AiPracticeOutboxPublishJob.class,
            AiPracticeWebSocketEventPublisher.class,
            AiPracticeGenerationCallbackController.class
        }
    )
    static class ChatRuntimeComponentsConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @Import({ChatRuntimeComponentsConfiguration.class, ChatWebSocketConfig.class})
    static class AllChatRuntimeConfiguration {}
}
