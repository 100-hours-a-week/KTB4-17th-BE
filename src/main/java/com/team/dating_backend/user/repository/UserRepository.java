package com.team.dating_backend.user.repository;

import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT coalesce(max(user.id), 0) FROM User user")
    long findMaxUserId();

    @Query("""
        SELECT user.id FROM User user
        WHERE user.status = :status AND user.id > :afterId AND user.id <= :throughId
        ORDER BY user.id ASC
        """)
    List<Long> findIdsForRefresh(@Param("status") UserStatus status,
        @Param("afterId") long afterId, @Param("throughId") long throughId, Pageable pageable);

    boolean existsByIdAndStatusNot(Long userId, UserStatus status);

    @Query("""
        select user.id
        from User user
        where user.id in :userIds
          and user.status = :status
        """)
    List<Long> findIdsByStatus(
        @Param("userIds") Collection<Long> userIds,
        @Param("status") UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
