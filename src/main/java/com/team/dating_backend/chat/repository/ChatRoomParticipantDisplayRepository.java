package com.team.dating_backend.chat.repository;

import com.team.dating_backend.profile.entity.Profile;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ChatRoomParticipantDisplayRepository extends Repository<Profile, Long> {

    @Query("""
        select new com.team.dating_backend.chat.repository.ChatParticipantDisplayRow(
            profile.user.id, profile.nickname)
        from Profile profile
        where profile.user.id in :userIds
          and profile.nickname is not null
          and profile.deletedAt is null
        """)
    List<ChatParticipantDisplayRow> findByUserIds(@Param("userIds") Collection<Long> userIds);
}
