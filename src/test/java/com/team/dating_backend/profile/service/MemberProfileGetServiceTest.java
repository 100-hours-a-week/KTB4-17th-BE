package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.dto.response.MemberProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MemberProfileGetServiceTest {

    private ProfileRepository profileRepository;
    private ProfileImageGetService profileImageGetService;
    private LikeRepository likeRepository;
    private ChatRoomRepository chatRoomRepository;
    private UserBlockRepository userBlockRepository;
    private RecommendationItemRepository recommendationItemRepository;
    private MemberProfileGetService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        profileImageGetService = mock(ProfileImageGetService.class);
        likeRepository = mock(LikeRepository.class);
        chatRoomRepository = mock(ChatRoomRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        recommendationItemRepository = mock(RecommendationItemRepository.class);
        service = new MemberProfileGetService(profileRepository, profileImageGetService,
            likeRepository, chatRoomRepository, userBlockRepository, recommendationItemRepository);
    }

    @Test
    void 좋아요나_채팅방이_없어도_현재_추천_상대의_프로필을_조회한다() {
        given(recommendationItemRepository.existsEligibleCandidateInActiveBatch(5L, 21L))
            .willReturn(true);
        givenActiveProfile(UserStatus.ACTIVE);

        MemberProfileResponse response = service.getMemberProfile(5L, 21L);

        assertThat(response.memberId()).isEqualTo(21L);
        assertThat(response.nickname()).isEqualTo("하리");
        assertThat(response.age()).isEqualTo(29);
        assertThat(response.images()).containsExactly(
            new MemberProfileResponse.Image(
                701L,
                (short) 1,
                "https://example.com/701"));
    }

    @Test
    void 기존_좋아요_관계의_상세_조회는_추천_여부와_무관하게_허용한다() {
        given(likeRepository.hasProfileViewAccessBetween(5L, 21L)).willReturn(true);
        givenActiveProfile(UserStatus.ACTIVE);

        assertThat(service.getMemberProfile(5L, 21L).memberId()).isEqualTo(21L);
        verifyNoInteractions(chatRoomRepository, recommendationItemRepository);
    }

    @Test
    void 기존_채팅방의_상세_조회는_추천_여부와_무관하게_허용한다() {
        given(chatRoomRepository.existsVisibleBetweenUsers(5L, 21L)).willReturn(true);
        givenActiveProfile(UserStatus.ACTIVE);

        assertThat(service.getMemberProfile(5L, 21L).memberId()).isEqualTo(21L);
        verifyNoInteractions(recommendationItemRepository);
    }

    @Test
    void 추천과_좋아요와_채팅_관계가_모두_없으면_거절한다() {
        assertUnavailable(21L);
        verifyNoInteractions(profileRepository, profileImageGetService);
    }

    @Test
    void 차단_관계는_추천_여부를_확인하기_전에_거절한다() {
        given(userBlockRepository.existsActiveBlockBetween(5L, 21L)).willReturn(true);

        assertUnavailable(21L);
        verifyNoInteractions(likeRepository, chatRoomRepository, recommendationItemRepository,
            profileRepository, profileImageGetService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, 5L})
    void 잘못된_상대_ID나_본인_조회는_거절한다(Long memberId) {
        assertUnavailable(memberId);
        verifyNoInteractions(userBlockRepository, recommendationItemRepository, profileRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void 조회_관계가_있어도_비활성_상대는_거절한다(UserStatus status) {
        given(likeRepository.hasProfileViewAccessBetween(5L, 21L)).willReturn(true);
        givenActiveProfile(status);

        assertUnavailable(21L);
        verifyNoInteractions(profileImageGetService);
    }

    @Test
    void 추천_상대라도_프로필이_없거나_삭제되었으면_거절한다() {
        given(recommendationItemRepository.existsEligibleCandidateInActiveBatch(5L, 21L))
            .willReturn(true);

        assertUnavailable(21L);
        verifyNoInteractions(profileImageGetService);
    }

    private void givenActiveProfile(UserStatus status) {
        User member = mock(User.class);
        given(member.getStatus()).willReturn(status);
        given(member.getBirthDate()).willReturn(LocalDate.now().minusYears(29));
        Profile profile = mock(Profile.class);
        given(profile.getUser()).willReturn(member);
        given(profile.getNickname()).willReturn("하리");
        given(profileRepository.findByUserIdAndDeletedAtIsNull(21L))
            .willReturn(Optional.of(profile));
        given(profileImageGetService.getProfileImagesByMemberIds(List.of(21L)))
            .willReturn(Map.of(21L,
                List.of(new ProfileImageAccessResult(
                    701L,
                    (short) 1,
                    "https://example.com/701",
                    Instant.parse("2026-09-27T12:00:00Z")))));
    }

    private void assertUnavailable(Long memberId) {
        assertThatThrownBy(() -> service.getMemberProfile(5L, memberId))
            .isInstanceOf(ProfileImageBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ProfileImageBusinessException) exception).getErrorCode())
                .isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));
    }
}
