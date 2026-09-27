package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.user.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileImageGetServiceTest {

    private ProfileImageRepository profileImageRepository;
    private FileAccessUrlCreateService fileAccessUrlCreateService;
    private ProfileImageGetService service;

    @BeforeEach
    void setUp() {
        profileImageRepository = mock(ProfileImageRepository.class);
        fileAccessUrlCreateService = mock(FileAccessUrlCreateService.class);
        service = new ProfileImageGetService(
            profileImageRepository, fileAccessUrlCreateService);
    }

    @Test
    void 회원별_프로필_이미지를_순서대로_반환한다() {
        ProfileImage member21First = profileImage(21L, 701L, (short) 1);
        ProfileImage member21Second = profileImage(21L, 704L, (short) 2);
        ProfileImage member22First = profileImage(22L, 801L, (short) 1);
        given(profileImageRepository.findActiveProfileImagesByMemberIds(List.of(21L, 22L)))
            .willReturn(List.of(member21First, member21Second, member22First));
        givenAccessUrl(member21First, "https://example.com/701");
        givenAccessUrl(member21Second, "https://example.com/704");
        givenAccessUrl(member22First, "https://example.com/801");

        Map<Long, List<ProfileImageAccessResult>> result = service.getProfileImagesByMemberIds(List.of(21L, 22L));

        assertThat(result.get(21L))
            .extracting(ProfileImageAccessResult::fileId)
            .containsExactly(701L, 704L);
        assertThat(result.get(21L))
            .extracting(ProfileImageAccessResult::displayOrder)
            .containsExactly((short) 1, (short) 2);
        assertThat(result.get(21L))
            .extracting(ProfileImageAccessResult::imageUrl)
            .containsExactly("https://example.com/701", "https://example.com/704");
        assertThat(result.get(22L))
            .extracting(ProfileImageAccessResult::fileId)
            .containsExactly(801L);
    }

    @Test
    void 회원이_없으면_프로필_이미지를_조회하지_않는다() {
        Map<Long, List<ProfileImageAccessResult>> result = service.getProfileImagesByMemberIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(profileImageRepository, fileAccessUrlCreateService);
    }

    @Test
    void 프로필_이미지_URL_생성에_실패하면_예외를_전파한다() {
        ProfileImage profileImage = profileImage(21L, 701L, (short) 1);
        given(profileImageRepository.findActiveProfileImagesByMemberIds(List.of(21L)))
            .willReturn(List.of(profileImage));
        RuntimeException urlCreateException = new RuntimeException("URL create failed");
        given(fileAccessUrlCreateService.createPresignedAccessUrl(
            profileImage.getImage(), "inline"))
            .willThrow(urlCreateException);

        assertThatThrownBy(() -> service.getProfileImagesByMemberIds(List.of(21L)))
            .isSameAs(urlCreateException);
    }

    private ProfileImage profileImage(Long memberId, Long fileId, short displayOrder) {
        User member = mock(User.class);
        given(member.getId()).willReturn(memberId);
        Profile profile = mock(Profile.class);
        given(profile.getUser()).willReturn(member);
        File file = mock(File.class);
        given(file.getId()).willReturn(fileId);
        ProfileImage profileImage = mock(ProfileImage.class);
        given(profileImage.getProfile()).willReturn(profile);
        given(profileImage.getImage()).willReturn(file);
        given(profileImage.getDisplayOrder()).willReturn(displayOrder);
        return profileImage;
    }

    private void givenAccessUrl(ProfileImage profileImage, String imageUrl) {
        File image = profileImage.getImage();
        Long fileId = image.getId();
        given(fileAccessUrlCreateService.createPresignedAccessUrl(image, "inline"))
            .willReturn(new FileAccessUrlResult(
                fileId, imageUrl, "inline", Instant.parse("2026-09-27T12:00:00Z")));
    }
}
