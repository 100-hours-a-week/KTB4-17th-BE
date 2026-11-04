package com.team.dating_backend.profile.repository;

import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.user.enums.UserStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    Optional<Profile> findByUserIdAndDeletedAtIsNull(Long userId);

    boolean existsByUserIdAndUserStatusAndDeletedAtIsNull(Long userId, UserStatus status);

    boolean existsByNicknameAndDeletedAtIsNullAndUserIdNot(String nickname, Long userId);
}
