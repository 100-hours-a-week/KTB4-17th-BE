package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageImageAccessService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final FileAccessUrlCreateService fileAccessUrlCreateService;

    public ChatMessageImageAccessService(
        ChatRoomRepository chatRoomRepository,
        ChatParticipantRepository chatParticipantRepository,
        ChatMessageRepository chatMessageRepository,
        FileAccessUrlCreateService fileAccessUrlCreateService) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.fileAccessUrlCreateService = fileAccessUrlCreateService;
    }

    @Transactional(readOnly = true)
    public FileAccessUrlResult createAccessUrl(
        Long chatRoomId, Long fileId, Long viewerUserId) {
        if (chatRoomId == null || chatRoomId <= 0 || fileId == null || fileId <= 0
            || viewerUserId == null || viewerUserId <= 0) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_IMAGE_NOT_FOUND);
        }

        var room = chatRoomRepository.findById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        if (room.getStatus() == ChatRoomStatus.INACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        var participant = chatParticipantRepository
            .findByChatRoomIdAndUserId(chatRoomId, viewerUserId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED));
        if (participant.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        var imageFiles = chatMessageRepository.findImageFiles(
            chatRoomId,
            fileId,
            ChatMessageType.IMAGE,
            ChatMessageStatus.SENT,
            PageRequest.of(0, 1));
        var imageFile = imageFiles.stream()
            .findFirst()
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_IMAGE_NOT_FOUND));
        return fileAccessUrlCreateService.createPresignedAccessUrl(imageFile, "inline");
    }
}
