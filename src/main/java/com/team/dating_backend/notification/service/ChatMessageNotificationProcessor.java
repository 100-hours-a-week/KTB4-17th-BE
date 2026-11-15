package com.team.dating_backend.notification.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.presence.ChatRoomPresenceRegistry;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.ChatNotificationTemplate;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatMessageNotificationProcessor {

    private static final String SOURCE_EVENT_ID_PREFIX = "CHAT_MESSAGE_SENT:";

    private final ChatRoomPresenceRegistry presenceRegistry;
    private final ProfileRepository profileRepository;
    private final NotificationCreationProcessor notificationCreationProcessor;

    public void process(ChatMessageCreatedKafkaEvent event)
        throws ExecutionException, InterruptedException, TimeoutException {
        if (!event.isUnreadByReceiver()
            || presenceRegistry.isViewingChatRoom(event.receiverUserId(), event.chatRoomId())) {
            return;
        }

        String senderNickname = profileRepository.findByUserIdAndDeletedAtIsNull(event.senderUserId())
            .map(Profile::getNickname)
            .orElse(null);
        ChatNotificationTemplate template = ChatNotificationTemplate.CHAT_MESSAGE;
        notificationCreationProcessor
            .process(new NotificationCreateRequested(SOURCE_EVENT_ID_PREFIX + event.messageId(),
                event.receiverUserId(),
                NotificationType.CHAT_MESSAGE,
                template.getTitle(),
                template.formatBody(senderNickname),
                template.getTargetType(),
                event.chatRoomId(),
                Instant.now()));
    }
}
