package com.team.dating_backend.user.repository;

import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

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
