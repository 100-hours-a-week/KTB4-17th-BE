package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageOutboxPublishJobTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long SENDER_PARTICIPANT_ID = 10L;
    private static final Long RECEIVER_PARTICIPANT_ID = 20L;
    private static final String MESSAGE_DESTINATION = "/queue/chat-messages";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 26, 14, 30);

    private ChatMessageOutboxRepository outboxRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private SimpMessagingTemplate messagingTemplate;
    private ChatMessageOutboxPublishJob job;
    private ChatMessageOutbox outbox;
    private ChatMessage message;
    private ChatParticipant sender;
    private ChatParticipant receiver;

    @BeforeEach
    void setUp() {
        outboxRepository = org.mockito.Mockito.mock(ChatMessageOutboxRepository.class);
        chatMessageRepository = org.mockito.Mockito.mock(ChatMessageRepository.class);
        chatParticipantRepository = org.mockito.Mockito.mock(ChatParticipantRepository.class);
        messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        job = new ChatMessageOutboxPublishJob(
            outboxRepository,
            chatMessageRepository,
            chatParticipantRepository,
            messagingTemplate);

        outbox = new ChatMessageOutbox(100L, CREATED_AT);
        message = new ChatMessage(
            CHAT_ROOM_ID, SENDER_PARTICIPANT_ID, UUID.randomUUID(), "안녕하세요", CREATED_AT);
        ReflectionTestUtils.setField(message, "id", 100L);

        sender = participant(SENDER_PARTICIPANT_ID, 1L, ChatParticipantStatus.ACTIVE);
        receiver = participant(RECEIVER_PARTICIPANT_ID, 2L, ChatParticipantStatus.ACTIVE);

        given(outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class)))
            .willReturn(List.of(outbox));
        given(chatMessageRepository.findById(100L)).willReturn(Optional.of(message));
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(sender, receiver));
    }

    @Test
    void 정상_발행하면_두_활성_참여자에게_개별_이벤트를_보내고_Outbox를_완료한다() {
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        job.publishDueMessages();

        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getFailedAt()).isNull();
        assertThat(outbox.getFailureCount()).isZero();
        verify(messagingTemplate, times(2)).convertAndSendToUser(
            userIdCaptor.capture(), eq(MESSAGE_DESTINATION), eventCaptor.capture());
        assertThat(userIdCaptor.getAllValues()).containsExactly("1", "2");
        assertThat(eventCaptor.getAllValues())
            .allSatisfy(event -> assertThat(event).isInstanceOf(ChatMessageCreatedEvent.class));
        assertThat(eventCaptor.getAllValues())
            .extracting(event -> ((ChatMessageCreatedEvent) event).mine())
            .containsExactly(true, false);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void 원본_메시지가_없으면_재시도하지_않고_Outbox를_실패_격리한다() {
        given(chatMessageRepository.findById(100L)).willReturn(Optional.empty());

        job.publishDueMessages();

        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.MESSAGE_NOT_FOUND.name());
        verify(outboxRepository).save(outbox);
        verifyNoInteractions(chatParticipantRepository, messagingTemplate);
    }

    @Test
    void 참여자_데이터가_유효하지_않으면_발행하지_않고_실패_격리한다() {
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(sender));

        job.publishDueMessages();

        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.PARTICIPANTS_INVALID.name());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void 브로커_실패는_실패_횟수를_기록하고_다음_시각으로_재예약한다() {
        ReflectionTestUtils.setField(job, "maxFailures", 3);
        doThrow(new IllegalStateException()).when(messagingTemplate)
            .convertAndSendToUser(anyString(), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getFailedAt()).isNull();
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getNextAttemptAt()).isAfter(CREATED_AT);
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void 브로커_실패가_설정된_한도에_도달하면_Outbox를_실패_격리한다() {
        ReflectionTestUtils.setField(job, "maxFailures", 1);
        doThrow(new IllegalStateException()).when(messagingTemplate)
            .convertAndSendToUser(anyString(), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void DB_조회_장애는_메시지별_실패로_기록하지_않고_작업_재시도에_백오프를_적용한다() {
        given(outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class)))
            .willThrow(new DataAccessResourceFailureException(
                ChatOutboxFailureCode.DATABASE_ACCESS_FAILED.name()));

        job.publishDueMessages();
        job.publishDueMessages();

        assertThat(outbox.getFailureCount()).isZero();
        assertThat(outbox.getFailedAt()).isNull();
        verify(outboxRepository, times(1))
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
        verifyNoInteractions(chatMessageRepository, chatParticipantRepository, messagingTemplate);
    }

    private ChatParticipant participant(
        Long participantId, Long userId, ChatParticipantStatus status) {
        ChatParticipant participant = new ChatParticipant(CHAT_ROOM_ID, userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        ReflectionTestUtils.setField(participant, "status", status);
        return participant;
    }
}
