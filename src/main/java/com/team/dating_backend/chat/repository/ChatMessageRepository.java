package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.file.entity.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @Query("""
        select message
        from ChatMessage message
        where message.senderParticipant.id = :senderParticipantId
          and message.clientMessageId = :clientMessageId
        """)
    Optional<ChatMessage> findBySenderParticipantIdAndClientMessageId(
        @Param("senderParticipantId") Long senderParticipantId,
        @Param("clientMessageId") UUID clientMessageId);

    @Query("""
        select message
        from ChatMessage message
        where message.id = :messageId
          and message.chatRoom.id = :chatRoomId
          and message.status = :status
        """)
    Optional<ChatMessage> findByIdAndChatRoomIdAndStatus(
        @Param("messageId") Long messageId,
        @Param("chatRoomId") Long chatRoomId,
        @Param("status") ChatMessageStatus status);

    @Query("""
        select message
        from ChatMessage message
        left join fetch message.imageFile
        where message.id = :messageId
        """)
    Optional<ChatMessage> findByIdWithImageFile(@Param("messageId") Long messageId);

    @Query("""
        select case when count(message.id) > 0 then true else false end
        from ChatMessage message
        where message.id = :messageId
          and message.chatRoom.id = :chatRoomId
          and message.status = :status
        """)
    boolean existsByIdAndChatRoomIdAndStatus(
        @Param("messageId") Long messageId,
        @Param("chatRoomId") Long chatRoomId,
        @Param("status") ChatMessageStatus status);

    @Query("""
        select message.imageFile
        from ChatMessage message
        where message.chatRoom.id = :chatRoomId
          and message.imageFile.id = :fileId
          and message.messageType = :messageType
          and message.status = :status
        order by message.id desc
        """)
    List<File> findImageFiles(
        @Param("chatRoomId") Long chatRoomId,
        @Param("fileId") Long fileId,
        @Param("messageType") ChatMessageType messageType,
        @Param("status") ChatMessageStatus status,
        Pageable pageable);

    @Query("""
        select message
        from ChatMessage message
        where message.chatRoom.id = :chatRoomId
          and message.status = :status
        order by message.id desc
        """)
    List<ChatMessage> findByChatRoomIdAndStatusOrderByIdDesc(
        @Param("chatRoomId") Long chatRoomId,
        @Param("status") ChatMessageStatus status,
        Pageable pageable);

    @Query("""
        select message
        from ChatMessage message
        where message.chatRoom.id = :chatRoomId
          and message.status = :status
          and message.id < :cursorMessageId
        order by message.id desc
        """)
    List<ChatMessage> findByChatRoomIdAndStatusAndIdLessThanOrderByIdDesc(
        @Param("chatRoomId") Long chatRoomId,
        @Param("status") ChatMessageStatus status,
        @Param("cursorMessageId") Long cursorMessageId,
        Pageable pageable);
}
