package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatParticipant;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    @Query("""
        select target.userId
        from ChatParticipant target
        join ChatParticipant viewer on viewer.chatRoom.id = target.chatRoom.id
        where viewer.userId = :viewerUserId
          and viewer.status = com.team.dating_backend.chat.enums.ChatParticipantStatus.ACTIVE
          and target.userId in :targetUserIds
          and target.status = com.team.dating_backend.chat.enums.ChatParticipantStatus.LEFT
        """)
    List<Long> findLeftUserIdsVisibleTo(
        @Param("viewerUserId") Long viewerUserId,
        @Param("targetUserIds") Collection<Long> targetUserIds);

    @Query("select participant from ChatParticipant participant where participant.chatRoom.id = :chatRoomId")
    List<ChatParticipant> findAllByChatRoomId(@Param("chatRoomId") Long chatRoomId);

    @Query("""
        select participant
        from ChatParticipant participant
        where participant.chatRoom.id = :chatRoomId
          and participant.userId = :userId
        """)
    Optional<ChatParticipant> findByChatRoomIdAndUserId(
        @Param("chatRoomId") Long chatRoomId,
        @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select participant
        from ChatParticipant participant
        where participant.chatRoom.id = :chatRoomId
          and participant.userId = :userId
        """)
    Optional<ChatParticipant> findByChatRoomAndUserForUpdate(
        @Param("chatRoomId") Long chatRoomId,
        @Param("userId") Long userId);
}
