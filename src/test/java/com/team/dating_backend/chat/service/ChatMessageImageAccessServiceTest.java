package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.matching.entity.Match;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageImageAccessServiceTest {

    private static final Long ROOM_ID = 30L;
    private static final Long FILE_ID = 900L;
    private static final Long VIEWER_ID = 2L;

    private ChatRoomRepository chatRoomRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatMessageRepository chatMessageRepository;
    private FileAccessUrlCreateService fileAccessUrlCreateService;
    private ChatMessageImageAccessService service;
    private File imageFile;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        fileAccessUrlCreateService = mock(FileAccessUrlCreateService.class);
        service = new ChatMessageImageAccessService(
            chatRoomRepository,
            chatParticipantRepository,
            chatMessageRepository,
            fileAccessUrlCreateService);

        Match match = new Match(1L, VIEWER_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(match, "id", 70L);
        ChatRoom room = new ChatRoom(match, LocalDateTime.now());
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        ChatParticipant participant = room.addParticipant(VIEWER_ID);
        ReflectionTestUtils.setField(participant, "id", 20L);
        given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
        given(chatParticipantRepository.findByChatRoomIdAndUserId(ROOM_ID, VIEWER_ID))
            .willReturn(Optional.of(participant));

        imageFile = File.create(1L, "files/shared.jpg", "shared.jpg", "image/jpeg", 100L);
        ReflectionTestUtils.setField(imageFile, "id", FILE_ID);
        given(chatMessageRepository.findImageFiles(
            ROOM_ID, FILE_ID, com.team.dating_backend.chat.enums.ChatMessageType.IMAGE,
            com.team.dating_backend.chat.enums.ChatMessageStatus.SENT, PageRequest.of(0, 1)))
            .willReturn(List.of(imageFile));
        given(fileAccessUrlCreateService.createPresignedAccessUrl(imageFile, "inline"))
            .willReturn(new FileAccessUrlResult(
                FILE_ID, "https://example.test/signed", "inline", Instant.now()));
    }

    @Test
    void 채팅방_참여자는_상대가_공유한_이미지의_서명_URL을_받는다() {
        FileAccessUrlResult result = service.createAccessUrl(ROOM_ID, FILE_ID, VIEWER_ID);

        assertThat(result.fileId()).isEqualTo(FILE_ID);
        assertThat(result.disposition()).isEqualTo("inline");
        verify(fileAccessUrlCreateService).createPresignedAccessUrl(imageFile, "inline");
    }

    @Test
    void 채팅방_비참여자에게는_파일_URL을_발급하지_않는다() {
        given(chatParticipantRepository.findByChatRoomIdAndUserId(ROOM_ID, 99L))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAccessUrl(ROOM_ID, FILE_ID, 99L))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ACCESS_DENIED));
        verify(chatMessageRepository, never()).findImageFiles(
            ROOM_ID, FILE_ID, com.team.dating_backend.chat.enums.ChatMessageType.IMAGE,
            com.team.dating_backend.chat.enums.ChatMessageStatus.SENT, PageRequest.of(0, 1));
        verify(fileAccessUrlCreateService, never())
            .createPresignedAccessUrl(imageFile, "inline");
    }

    @Test
    void 다른_채팅방에서_공유하지_않은_파일에는_URL을_발급하지_않는다() {
        given(chatMessageRepository.findImageFiles(
            ROOM_ID, FILE_ID, com.team.dating_backend.chat.enums.ChatMessageType.IMAGE,
            com.team.dating_backend.chat.enums.ChatMessageStatus.SENT, PageRequest.of(0, 1)))
            .willReturn(List.of());

        assertThatThrownBy(() -> service.createAccessUrl(ROOM_ID, FILE_ID, VIEWER_ID))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_FOUND));
        verify(fileAccessUrlCreateService, never())
            .createPresignedAccessUrl(imageFile, "inline");
    }
}
