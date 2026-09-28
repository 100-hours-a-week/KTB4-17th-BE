package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.repository.FileRepository;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveItemRequest;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.dto.response.ProfileImageSaveResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProfileImageSaveServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROFILE_ID = 10L;

    private ProfileRepository profileRepository;
    private ProfileImageRepository profileImageRepository;
    private FileRepository fileRepository;
    private OnboardingCompletionService onboardingCompletionService;
    private ProfileImageSaveService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        profileImageRepository = mock(ProfileImageRepository.class);
        fileRepository = mock(FileRepository.class);
        onboardingCompletionService = mock(OnboardingCompletionService.class);
        service = new ProfileImageSaveService(
            profileRepository,
            profileImageRepository,
            fileRepository,
            onboardingCompletionService);
    }

    @Test
    void 요청_배열_순서대로_이미지를_저장하고_첫_사진을_대표순서로_반환한다() {
        Profile profile = profile();
        File first = file(701L);
        File frontal = file(704L);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(fileRepository.findAllActiveByIdsAndOwner(List.of(701L, 704L), USER_ID))
            .willReturn(List.of(first, frontal));
        given(profileImageRepository.findByProfileIdAndDeletedAtIsNull(PROFILE_ID))
            .willReturn(List.of());

        ProfileImageSaveResponse response = service.saveProfileImages(
            USER_ID,
            request(
                new ProfileImageSaveItemRequest(701L, false),
                new ProfileImageSaveItemRequest(704L, true)));

        assertThat(response.images()).hasSize(2);
        assertThat(response.images().get(0).fileId()).isEqualTo(701L);
        assertThat(response.images().get(0).displayOrder()).isEqualTo((short) 1);
        assertThat(response.images().get(0).isFrontal()).isFalse();
        assertThat(response.images().get(1).fileId()).isEqualTo(704L);
        assertThat(response.images().get(1).displayOrder()).isEqualTo((short) 2);
        assertThat(response.images().get(1).isFrontal()).isTrue();
        verify(profileImageRepository).saveAll(any());
        verify(onboardingCompletionService).activateIfCompleted(USER_ID);
    }

    @Test
    void 기존_연결은_재사용하고_누락된_연결은_논리삭제한다() {
        Profile profile = profile();
        File first = file(701L);
        File second = file(704L);
        File added = file(703L);
        ProfileImage existingFirst = new ProfileImage(profile, first, (short) 1, true);
        ProfileImage existingSecond = new ProfileImage(profile, second, (short) 2, false);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(fileRepository.findAllActiveByIdsAndOwner(List.of(704L, 703L), USER_ID))
            .willReturn(List.of(second, added));
        given(profileImageRepository.findByProfileIdAndDeletedAtIsNull(PROFILE_ID))
            .willReturn(List.of(existingFirst, existingSecond));

        ProfileImageSaveResponse response = service.saveProfileImages(
            USER_ID,
            request(
                new ProfileImageSaveItemRequest(704L, true),
                new ProfileImageSaveItemRequest(703L, false)));

        assertThat(existingFirst.getDeletedAt()).isNotNull();
        assertThat(existingSecond.getDeletedAt()).isNull();
        assertThat(existingSecond.getDisplayOrder()).isEqualTo((short) 1);
        assertThat(existingSecond.isFrontal()).isTrue();
        assertThat(response.images()).extracting("fileId").containsExactly(704L, 703L);
    }

    @Test
    void 같은_요청을_반복하면_기존_프로필_이미지를_재사용한다() {
        Profile profile = profile();
        File file = file(701L);
        ProfileImage existing = new ProfileImage(profile, file, (short) 1, true);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(fileRepository.findAllActiveByIdsAndOwner(List.of(701L), USER_ID))
            .willReturn(List.of(file));
        given(profileImageRepository.findByProfileIdAndDeletedAtIsNull(PROFILE_ID))
            .willReturn(List.of(existing));

        service.saveProfileImages(
            USER_ID,
            request(new ProfileImageSaveItemRequest(701L, true)));

        verify(profileImageRepository).saveAll(List.of());
        assertThat(existing.getDeletedAt()).isNull();
    }

    @Test
    void 정면사진이_없으면_FRONT_PHOTO_REQUIRED가_발생한다() {
        assertThatThrownBy(() -> service.saveProfileImages(
            USER_ID,
            request(new ProfileImageSaveItemRequest(701L, false))))
            .isInstanceOfSatisfying(
                ProfileImageBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ProfileImageErrorCode.FRONT_PHOTO_REQUIRED));

        verifyNoInteractions(profileRepository, profileImageRepository, fileRepository);
    }

    @Test
    void 정면사진이_두장이면_INVALID_REQUEST가_발생한다() {
        assertThatThrownBy(() -> service.saveProfileImages(
            USER_ID,
            request(
                new ProfileImageSaveItemRequest(701L, true),
                new ProfileImageSaveItemRequest(704L, true))))
            .isInstanceOf(RequestValidationException.class);

        verifyNoInteractions(profileRepository, profileImageRepository, fileRepository);
    }

    @Test
    void 파일ID가_중복되면_INVALID_REQUEST가_발생한다() {
        assertThatThrownBy(() -> service.saveProfileImages(
            USER_ID,
            request(
                new ProfileImageSaveItemRequest(701L, true),
                new ProfileImageSaveItemRequest(701L, false))))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void 사용할_수_없는_파일이_하나라도_있으면_프로필_이미지를_저장하지_않는다() {
        Profile profile = profile();
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(fileRepository.findAllActiveByIdsAndOwner(List.of(701L, 704L), USER_ID))
            .willReturn(List.of(file(701L)));

        assertThatThrownBy(() -> service.saveProfileImages(
            USER_ID,
            request(
                new ProfileImageSaveItemRequest(701L, true),
                new ProfileImageSaveItemRequest(704L, false))))
            .isInstanceOfSatisfying(
                ProfileImageBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ProfileImageErrorCode.FILE_NOT_AVAILABLE));

        verifyNoInteractions(profileImageRepository);
    }

    @Test
    void 프로필이_없으면_PROFILE_NOT_FOUND가_발생한다() {
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveProfileImages(
            USER_ID,
            request(new ProfileImageSaveItemRequest(701L, true))))
            .isInstanceOfSatisfying(
                ProfileImageBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));

        verifyNoInteractions(profileImageRepository, fileRepository);
    }

    private ProfileImageSaveRequest request(ProfileImageSaveItemRequest... items) {
        return new ProfileImageSaveRequest(List.of(items));
    }

    private Profile profile() {
        Profile profile = mock(Profile.class);
        given(profile.getId()).willReturn(PROFILE_ID);
        return profile;
    }

    private File file(Long id) {
        File file = File.create(USER_ID, "files/" + id, "photo.png", "image/png", 32L);
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }
}
