package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomCreateServiceTest {

    private ChatRoomRepository chatRoomRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatRoomCreateService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        service = new ChatRoomCreateService(
            chatRoomRepository, chatParticipantRepository);
    }

    @Test
    void Match로_채팅방을_생성하고_두_참여자를_등록한다() {
        LocalDateTime matchedAt = LocalDateTime.of(2026, 9, 26, 14, 30);
        given(chatRoomRepository.findByMatchId(30L)).willReturn(Optional.empty());
        given(chatRoomRepository.save(org.mockito.ArgumentMatchers.any(ChatRoom.class)))
            .willAnswer(invocation -> {
                ChatRoom chatRoom = invocation.getArgument(0);
                ReflectionTestUtils.setField(chatRoom, "id", 40L);
                return chatRoom;
            });
        List<ChatParticipant> savedParticipants = captureSavedParticipants();

        Long chatRoomId = service.createChatRoom(30L, 2L, 1L, matchedAt);

        assertThat(chatRoomId).isEqualTo(40L);
        ArgumentCaptor<ChatRoom> chatRoomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
        verify(chatRoomRepository).save(chatRoomCaptor.capture());
        ChatRoom chatRoom = chatRoomCaptor.getValue();
        assertThat(chatRoom.getMatchId()).isEqualTo(30L);
        assertThat(chatRoom.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(chatRoom.getCreatedAt()).isEqualTo(matchedAt);
        assertParticipants(savedParticipants);
    }

    @Test
    void 동일한_Match의_채팅방이_있으면_기존_ID를_반환한다() {
        ChatRoom existingChatRoom = mock(ChatRoom.class);
        given(existingChatRoom.getId()).willReturn(40L);
        given(chatRoomRepository.findByMatchId(30L))
            .willReturn(Optional.of(existingChatRoom));

        Long chatRoomId = service.createChatRoom(
            30L, 2L, 1L, LocalDateTime.of(2026, 9, 26, 14, 30));

        assertThat(chatRoomId).isEqualTo(40L);
        verify(chatRoomRepository, never())
            .save(org.mockito.ArgumentMatchers.any(ChatRoom.class));
        verifyNoInteractions(chatParticipantRepository);
    }

    @Test
    void 참여자_등록_실패를_상위_호출로_전달한다() {
        LocalDateTime matchedAt = LocalDateTime.of(2026, 9, 26, 14, 30);
        IllegalStateException failure = new IllegalStateException("participant save failed");
        given(chatRoomRepository.findByMatchId(30L)).willReturn(Optional.empty());
        given(chatRoomRepository.save(org.mockito.ArgumentMatchers.any(ChatRoom.class)))
            .willAnswer(invocation -> {
                ChatRoom chatRoom = invocation.getArgument(0);
                ReflectionTestUtils.setField(chatRoom, "id", 40L);
                return chatRoom;
            });
        given(chatParticipantRepository.saveAll(any())).willThrow(failure);

        assertThatThrownBy(() -> service.createChatRoom(30L, 2L, 1L, matchedAt))
            .isSameAs(failure);
    }

    private List<ChatParticipant> captureSavedParticipants() {
        List<ChatParticipant> savedParticipants = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<ChatParticipant> participants = invocation.getArgument(0);
            participants.forEach(savedParticipants::add);
            return savedParticipants;
        }).when(chatParticipantRepository).saveAll(any());
        return savedParticipants;
    }

    private void assertParticipants(List<ChatParticipant> participants) {
        assertThat(participants).hasSize(2);
        assertThat(participants)
            .extracting(ChatParticipant::getChatRoomId)
            .containsOnly(40L);
        assertThat(participants)
            .extracting(ChatParticipant::getUserId)
            .containsExactly(2L, 1L);
        assertThat(participants)
            .extracting(ChatParticipant::getStatus)
            .containsOnly(ChatParticipantStatus.ACTIVE);
        assertThat(participants)
            .allMatch(ChatParticipant::isChatNotification)
            .allMatch(participant -> participant.getLeftAt() == null)
            .allMatch(participant -> participant.getLastReadMessageId() == null);
    }
}
