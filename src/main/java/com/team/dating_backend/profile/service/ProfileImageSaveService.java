package com.team.dating_backend.profile.service;

import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.repository.FileRepository;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveItemRequest;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.dto.response.ProfileImageResponse;
import com.team.dating_backend.profile.dto.response.ProfileImageSaveResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileImageSaveService {

    private static final String IMAGES_FIELD = "images";

    private final ProfileRepository profileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final FileRepository fileRepository;

    @Transactional
    public ProfileImageSaveResponse saveProfileImages(
        Long userId,
        ProfileImageSaveRequest request) {
        validateRequest(request);

        Profile profile = profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .orElseThrow(
                () -> new ProfileImageBusinessException(
                    ProfileImageErrorCode.PROFILE_NOT_FOUND));

        List<Long> requestedFileIds = request.images().stream()
            .map(ProfileImageSaveItemRequest::fileId)
            .toList();
        Map<Long, File> filesById = findAvailableFiles(userId, requestedFileIds);

        List<ProfileImage> existingImages = profileImageRepository
            .findByProfileIdAndDeletedAtIsNull(profile.getId());
        Map<Long, ProfileImage> existingByFileId = existingImages.stream()
            .collect(Collectors.toMap(image -> image.getImage().getId(), Function.identity()));

        List<ProfileImage> orderedProfileImages = new ArrayList<>(request.images().size());
        List<ProfileImage> newImages = new ArrayList<>();
        for (int index = 0; index < request.images().size(); index++) {
            ProfileImageSaveItemRequest item = request.images().get(index);
            Long fileId = item.fileId();

            ProfileImage profileImage = existingByFileId.get(fileId);
            if (profileImage == null) {
                profileImage = new ProfileImage(
                    profile,
                    filesById.get(fileId),
                    (short) (index + 1),
                    item.isFrontal());
                newImages.add(profileImage);
            } else {
                profileImage.updateProfileImage((short) (index + 1), item.isFrontal());
            }
            orderedProfileImages.add(profileImage);
        }

        LocalDateTime deletedAt = LocalDateTime.now();
        existingImages.stream()
            .filter(image -> !filesById.containsKey(image.getImage().getId()))
            .forEach(image -> image.markDeleted(deletedAt));

        profileImageRepository.saveAll(newImages);

        List<ProfileImageResponse> responseImages = orderedProfileImages.stream()
            .map(profileImage -> new ProfileImageResponse(
                profileImage.getImage().getId(),
                profileImage.getDisplayOrder(),
                profileImage.isFrontal()))
            .toList();
        return new ProfileImageSaveResponse(responseImages);
    }

    private void validateRequest(ProfileImageSaveRequest request) {
        List<ProfileImageSaveItemRequest> images = request.images();
        long distinctFileIdCount = images.stream()
            .map(ProfileImageSaveItemRequest::fileId)
            .distinct()
            .count();
        if (distinctFileIdCount != images.size()) {
            throw invalidImages("must not contain duplicate file IDs");
        }

        long frontalCount = images.stream()
            .filter(ProfileImageSaveItemRequest::isFrontal)
            .count();
        if (frontalCount == 0) {
            throw new ProfileImageBusinessException(
                ProfileImageErrorCode.FRONT_PHOTO_REQUIRED);
        }
        if (frontalCount > 1) {
            throw invalidImages("must contain exactly one frontal photo");
        }
    }

    private Map<Long, File> findAvailableFiles(Long userId, List<Long> requestedFileIds) {
        List<File> files = fileRepository.findAllActiveByIdsAndOwner(requestedFileIds, userId);
        if (files.size() != requestedFileIds.size()) {
            throw new ProfileImageBusinessException(
                ProfileImageErrorCode.FILE_NOT_AVAILABLE);
        }

        return files.stream()
            .collect(Collectors.toMap(File::getId, Function.identity()));
    }

    private RequestValidationException invalidImages(String reason) {
        return new RequestValidationException(List.of(new FieldErrorResponse(IMAGES_FIELD, reason)));
    }
}
