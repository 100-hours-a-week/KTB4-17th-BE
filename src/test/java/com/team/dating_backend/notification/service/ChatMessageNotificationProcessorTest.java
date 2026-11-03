package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.presence.ChatRoomPresenceRegistry;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class ChatMessageNotificationProcessorTest {

    private final ChatRoomPresenceRegistry presenceRegistry = org.mockito.Mockito.mock(
        ChatRoomPresenceRegistry.class);
    private final ProfileRepository profileRepository = org.mockito.Mockito.mock(ProfileRepository.class);
    private final NotificationCreationProcessor notificationCreationProcessor = org.mockito.Mockito.mock(
        NotificationCreationProcessor.class);
    private final ChatMessageNotificationProcessor processor = new ChatMessageNotificationProcessor(
        presenceRegistry, profileRepository, notificationCreationProcessor);

    @Test
    void 수신자가_채팅방을_보고_있지_않고_메시지를_읽지_않았으면_알림을_생성한다() throws Exception {
        ChatMessageCreatedKafkaEvent event = event(true);
        Profile profile = org.mockito.Mockito.mock(Profile.class);
        given(presenceRegistry.isViewingChatRoom(2L, 10L)).willReturn(false);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(profile));
        given(profile.getNickname()).willReturn("보낸사람");

        processor.process(event);

        ArgumentCaptor<NotificationCreateRequested> request = ArgumentCaptor.forClass(
            NotificationCreateRequested.class);
        verify(notificationCreationProcessor).process(request.capture());
        assertThat(request.getValue())
            .extracting(
                NotificationCreateRequested::sourceEventId,
                NotificationCreateRequested::recipientUserId,
                NotificationCreateRequested::type,
                NotificationCreateRequested::targetId)
            .containsExactly("CHAT_MESSAGE_SENT:100", 2L, NotificationType.CHAT_MESSAGE, 10L);
        assertThat(request.getValue().body()).isEqualTo("보낸사람님에게서 메시지가 도착했어요.");
    }

    @ParameterizedTest
    @MethodSource("eventsWithoutNotification")
    void 수신자가_읽었거나_채팅방을_보고_있으면_알림을_생성하지_않는다(
        ChatMessageCreatedKafkaEvent event, boolean isViewingChatRoom) throws Exception {
        given(presenceRegistry.isViewingChatRoom(2L, 10L)).willReturn(isViewingChatRoom);

        processor.process(event);

        verify(profileRepository, never()).findByUserIdAndDeletedAtIsNull(1L);
        verify(notificationCreationProcessor, never()).process(org.mockito.ArgumentMatchers.any());
    }

    private static List<Object[]> eventsWithoutNotification() {
        return List.of(
            new Object[]{event(false), false},
            new Object[]{event(true), true});
    }

    private static ChatMessageCreatedKafkaEvent event(boolean isUnreadByReceiver) {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 3, 10, 0);
        return new ChatMessageCreatedKafkaEvent(
            "chat-message-created:100",
            occurredAt,
            10L,
            100L,
            UUID.fromString("d9dd8900-cf39-4bfd-bd8f-feb628016cb4"),
            1L,
            2L,
            List.of(1L, 2L),
            false,
            ChatMessageType.TEXT,
            "안녕하세요",
            null,
            isUnreadByReceiver,
            occurredAt);
    }
}
