package com.team.dating_backend.profile.service;

import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileImageGetService {

    private static final String INLINE_DISPOSITION = "inline";

    private final ProfileImageRepository profileImageRepository;
    private final FileAccessUrlCreateService fileAccessUrlCreateService;

    @Transactional(readOnly = true)
    public Map<Long, List<ProfileImageAccessResult>> getProfileImagesByMemberIds(
        Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ProfileImageAccessResult>> profileImagesByMemberId = new LinkedHashMap<>();
        for (ProfileImage profileImage : profileImageRepository
            .findActiveProfileImagesByMemberIds(memberIds)) {
            Long memberId = profileImage.getProfile().getUser().getId();
            FileAccessUrlResult accessUrl = fileAccessUrlCreateService.createPresignedAccessUrl(
                profileImage.getImage(), INLINE_DISPOSITION);
            ProfileImageAccessResult image = new ProfileImageAccessResult(
                profileImage.getImage().getId(),
                profileImage.getDisplayOrder(),
                accessUrl.accessUrl(),
                accessUrl.expiresAt());
            profileImagesByMemberId.computeIfAbsent(memberId, ignored -> new java.util.ArrayList<>())
                .add(image);
        }
        return profileImagesByMemberId;
    }
}
