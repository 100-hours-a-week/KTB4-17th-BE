package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @Query("select room from ChatRoom room where room.match.id = :matchId")
    Optional<ChatRoom> findByMatchId(@Param("matchId") Long matchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select room from ChatRoom room where room.id = :chatRoomId")
    Optional<ChatRoom> findWithLockById(@Param("chatRoomId") Long chatRoomId);

    @Query("""
        select new com.team.dating_backend.chat.repository.ChatRoomListRow(
            room.id, viewer.id, viewer.chatNotification, other.userId, room.status,
            lastMessage.id, lastMessage.senderParticipant.id, lastMessage.messageType,
            lastMessage.textContent, lastMessage.imageFile.id,
            lastMessage.senderDeletedAt,
            lastMessage.receiverDeletedAt,
            (select count(unreadMessage.id)
                from ChatMessage unreadMessage
                where unreadMessage.chatRoom.id = room.id
                  and unreadMessage.status = :sentMessageStatus
                  and unreadMessage.senderParticipant.id <> viewer.id
                  and (
                      viewer.lastReadMessageId is null
                      or unreadMessage.id > viewer.lastReadMessageId
                  )
            ),
            coalesce(lastMessage.createdAt, room.createdAt)
        )
        from ChatRoom room
        join ChatParticipant viewer
          on viewer.chatRoom.id = room.id
         and viewer.userId = :viewerUserId
         and viewer.status = :activeParticipantStatus
        join ChatParticipant other
          on other.chatRoom.id = room.id
         and other.userId <> :viewerUserId
        left join ChatMessage lastMessage
          on lastMessage.id = (
                select max(message.id) from ChatMessage message
                where message.chatRoom.id = room.id
                  and message.status = :sentMessageStatus
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
            List.of(ChatRoomStatus.ACTIVE, ChatRoomStatus.ENDED),
            cursorActivityAt,
            cursorRoomId,
            pageable);
    }

    @Query("""
        select count(message.id)
        from ChatParticipant viewer
        join viewer.chatRoom room
        join ChatMessage message
          on message.chatRoom.id = room.id
         and message.senderParticipant.id <> viewer.id
         and message.status = :sentMessageStatus
         and (
             viewer.lastReadMessageId is null
             or message.id > viewer.lastReadMessageId
         )
        where viewer.userId = :viewerUserId
          and viewer.status = :activeParticipantStatus
          and room.status in :visibleRoomStatuses
        """)
    long countUnreadMessages(
        @Param("viewerUserId") Long viewerUserId,
        @Param("activeParticipantStatus") ChatParticipantStatus activeParticipantStatus,
        @Param("sentMessageStatus") ChatMessageStatus sentMessageStatus,
        @Param("visibleRoomStatuses") List<ChatRoomStatus> visibleRoomStatuses);

    default long countVisibleUnreadMessages(Long viewerUserId) {
        return countUnreadMessages(
            viewerUserId,
            ChatParticipantStatus.ACTIVE,
            ChatMessageStatus.SENT,
            List.of(ChatRoomStatus.ACTIVE, ChatRoomStatus.ENDED));
    }
}
