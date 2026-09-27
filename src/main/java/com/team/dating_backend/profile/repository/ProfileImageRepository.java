package com.team.dating_backend.profile.repository;

import com.team.dating_backend.profile.entity.ProfileImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileImageRepository extends JpaRepository<ProfileImage, Long> {

    List<ProfileImage> findByProfileIdAndDeletedAtIsNull(Long profileId);

    boolean existsByImageIdAndDeletedAtIsNull(Long imageId);
}
