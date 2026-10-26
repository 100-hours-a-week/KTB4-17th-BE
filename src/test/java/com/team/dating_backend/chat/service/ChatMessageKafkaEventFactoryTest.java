package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.exception.ChatMessageKafkaEventCreationException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageKafkaEventFactoryTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long MESSAGE_ID = 100L;
    private static final Long SENDER_PARTICIPANT_ID = 10L;
    private static final Long RECEIVER_PARTICIPANT_ID = 20L;
    private static final Long SENDER_USER_ID = 1L;
    private static final Long RECEIVER_USER_ID = 2L;
    private static final UUID CLIENT_MESSAGE_ID = UUID.fromString(
        "d9dd8900-cf39-4bfd-bd8f-feb628016cb4");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 26, 20, 30);

    private ChatMessageRepository chatMessageRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatDelegationMessageRepository delegationMessageRepository;
    private ChatMessageKafkaEventFactory eventFactory;
    private ChatMessage message;
    private ChatParticipant sender;
    private ChatParticipant receiver;

    @BeforeEach
    void setUp() {
        chatMessageRepository = org.mockito.Mockito.mock(ChatMessageRepository.class);
        chatParticipantRepository = org.mockito.Mockito.mock(ChatParticipantRepository.class);
        delegationMessageRepository = org.mockito.Mockito.mock(
            ChatDelegationMessageRepository.class);
        eventFactory = new ChatMessageKafkaEventFactory(
            chatMessageRepository,
            chatParticipantRepository,
            delegationMessageRepository);

        ChatRoom room = org.mockito.Mockito.mock(ChatRoom.class);
        given(room.getId()).willReturn(CHAT_ROOM_ID);
        sender = participant(room, SENDER_PARTICIPANT_ID, SENDER_USER_ID);
        receiver = participant(room, RECEIVER_PARTICIPANT_ID, RECEIVER_USER_ID);
        receiver.advanceLastReadMessageId(MESSAGE_ID - 1);
        message = new ChatMessage(
            room, sender, CLIENT_MESSAGE_ID, "안녕하세요", CREATED_AT);
        ReflectionTestUtils.setField(message, "id", MESSAGE_ID);

        given(chatMessageRepository.findByIdWithImageFile(MESSAGE_ID))
            .willReturn(Optional.of(message));
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(receiver, sender));
    }

    @Test
    void 메시지와_참여자_정보로_Kafka_이벤트를_생성한다() {
        ChatMessageCreatedKafkaEvent event = eventFactory.create(MESSAGE_ID);

        assertThat(event.eventId()).isEqualTo("chat-message-created:100");
        assertThat(event.occurredAt()).isEqualTo(CREATED_AT);
        assertThat(event.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.clientMessageId()).isEqualTo(CLIENT_MESSAGE_ID);
        assertThat(event.senderUserId()).isEqualTo(SENDER_USER_ID);
        assertThat(event.receiverUserId()).isEqualTo(RECEIVER_USER_ID);
        assertThat(event.realtimeTargetUserIds())
            .containsExactly(SENDER_USER_ID, RECEIVER_USER_ID);
        assertThat(event.isDelegated()).isFalse();
        assertThat(event.messageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(event.textContent()).isEqualTo("안녕하세요");
        assertThat(event.imageFileId()).isNull();
        assertThat(event.isUnreadByReceiver()).isTrue();
        assertThat(event.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void AI_위임_메시지는_요청자를_실시간_전달_대상에서_제외한다() {
        given(delegationMessageRepository
            .findRequesterParticipantIdByChatMessageId(MESSAGE_ID))
            .willReturn(Optional.of(SENDER_PARTICIPANT_ID));

        ChatMessageCreatedKafkaEvent event = eventFactory.create(MESSAGE_ID);

        assertThat(event.isDelegated()).isTrue();
        assertThat(event.realtimeTargetUserIds()).containsExactly(RECEIVER_USER_ID);
    }

    @Test
    void 퇴장한_참여자는_실시간_전달_대상에서_제외한다() {
        receiver.leave(CREATED_AT.plusSeconds(1));

        ChatMessageCreatedKafkaEvent event = eventFactory.create(MESSAGE_ID);

        assertThat(event.realtimeTargetUserIds()).containsExactly(SENDER_USER_ID);
    }

    @Test
    void 원본_메시지가_없으면_실패_코드를_전달한다() {
        given(chatMessageRepository.findByIdWithImageFile(MESSAGE_ID))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> eventFactory.create(MESSAGE_ID))
            .isInstanceOfSatisfying(
                ChatMessageKafkaEventCreationException.class,
                exception -> assertThat(exception.getFailureCode())
                    .isEqualTo(ChatOutboxFailureCode.MESSAGE_NOT_FOUND));
    }

    @Test
    void 참여자_구성이_유효하지_않으면_실패_코드를_전달한다() {
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(sender));

        assertThatThrownBy(() -> eventFactory.create(MESSAGE_ID))
            .isInstanceOfSatisfying(
                ChatMessageKafkaEventCreationException.class,
                exception -> assertThat(exception.getFailureCode())
                    .isEqualTo(ChatOutboxFailureCode.PARTICIPANTS_INVALID));
    }

    private ChatParticipant participant(
        ChatRoom room, Long participantId, Long userId) {
        ChatParticipant participant = new ChatParticipant(room, userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        return participant;
    }
}
