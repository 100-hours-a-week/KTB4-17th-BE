package com.team.dating_backend.profile.repository;

import com.team.dating_backend.profile.entity.ProfileImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileImageRepository extends JpaRepository<ProfileImage, Long> {

    List<ProfileImage> findByProfileIdAndDeletedAtIsNull(Long profileId);

    boolean existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(Long profileId);

    boolean existsByImageIdAndDeletedAtIsNull(Long imageId);

    @Query("""
        SELECT profileImage
        FROM ProfileImage profileImage
        JOIN FETCH profileImage.image image
        JOIN FETCH profileImage.profile profile
        JOIN FETCH profile.user member
        WHERE member.id IN :memberIds
          AND profile.deletedAt IS NULL
          AND profileImage.deletedAt IS NULL
          AND image.deletedAt IS NULL
        ORDER BY member.id ASC, profileImage.displayOrder ASC, profileImage.id ASC
        """)
    List<ProfileImage> findActiveProfileImagesByMemberIds(
        @Param("memberIds") Collection<Long> memberIds);
}
