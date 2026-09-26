package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByMatchId(Long matchId);

    @Query("""
        select new com.team.dating_backend.chat.repository.ChatRoomListRow(
            room.id, viewer.id, viewer.chatNotification, other.userId, room.status,
            lastMessage.id, lastMessage.senderParticipantId, lastMessage.textContent,
            lastMessage.senderDeletedAt,
            lastMessage.receiverDeletedAt,
            coalesce(lastMessage.createdAt, room.createdAt)
        )
        from ChatRoom room
        join ChatParticipant viewer
          on viewer.chatRoomId = room.id
         and viewer.userId = :viewerUserId
         and viewer.status = :activeParticipantStatus
        join ChatParticipant other
          on other.chatRoomId = room.id
         and other.userId <> :viewerUserId
        left join ChatMessage lastMessage
          on lastMessage.id = (
              select max(message.id) from ChatMessage message
              where message.chatRoomId = room.id
                and message.status = :sentMessageStatus
                and message.messageType = :textMessageType
          )
        where room.status in :visibleRoomStatuses
          and (
              :cursorActivityAt is null
              or coalesce(lastMessage.createdAt, room.createdAt) < :cursorActivityAt
              or (
                  coalesce(lastMessage.createdAt, room.createdAt) = :cursorActivityAt
                  and room.id < :cursorRoomId
              )
          )
        order by coalesce(lastMessage.createdAt, room.createdAt) desc, room.id desc
        """)
    List<ChatRoomListRow> findRoomList(
        @Param("viewerUserId") Long viewerUserId,
        @Param("activeParticipantStatus") ChatParticipantStatus activeParticipantStatus,
        @Param("sentMessageStatus") ChatMessageStatus sentMessageStatus,
        @Param("textMessageType") ChatMessageType textMessageType,
        @Param("visibleRoomStatuses") List<ChatRoomStatus> visibleRoomStatuses,
        @Param("cursorActivityAt") LocalDateTime cursorActivityAt,
        @Param("cursorRoomId") Long cursorRoomId,
        Pageable pageable);

    default List<ChatRoomListRow> findVisibleRoomList(
        Long viewerUserId,
        LocalDateTime cursorActivityAt,
        Long cursorRoomId,
        Pageable pageable) {
        return findRoomList(
            viewerUserId,
            ChatParticipantStatus.ACTIVE,
            ChatMessageStatus.SENT,
            ChatMessageType.TEXT,
            List.of(ChatRoomStatus.ACTIVE, ChatRoomStatus.ENDED),
            cursorActivityAt,
            cursorRoomId,
            pageable);
    }
}
