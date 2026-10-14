package com.team.dating_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatRoomWithdrawalTerminationService;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.matching.service.LikeTerminationService;
import com.team.dating_backend.matching.service.MatchWithdrawalTerminationService;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class UserWithdrawalServiceTest {

    private static final Long USER_ID = Long.MAX_VALUE;

    private UserRepository userRepository;
    private UserAuthAccountRepository authAccountRepository;
    private ProfileRepository profileRepository;
    private ProfileImageRepository profileImageRepository;
    private LikeTerminationService likeTerminationService;
    private ChatRoomWithdrawalTerminationService chatRoomWithdrawalTerminationService;
    private MatchWithdrawalTerminationService matchWithdrawalTerminationService;
    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authAccountRepository = mock(UserAuthAccountRepository.class);
        profileRepository = mock(ProfileRepository.class);
        profileImageRepository = mock(ProfileImageRepository.class);
        likeTerminationService = mock(LikeTerminationService.class);
        chatRoomWithdrawalTerminationService = mock(ChatRoomWithdrawalTerminationService.class);
        matchWithdrawalTerminationService = mock(MatchWithdrawalTerminationService.class);
        service = new UserWithdrawalService(userRepository, authAccountRepository,
            profileRepository, profileImageRepository, likeTerminationService,
            chatRoomWithdrawalTerminationService, matchWithdrawalTerminationService);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "ONBOARDING", "SUSPENDED"})
    void 탈퇴하면_개인정보와_인증정보를_비식별화하고_프로필을_논리_삭제한다(UserStatus status) {
        User user = user(status);
        UserAuthAccount account = UserAuthAccount.create(
            user, AuthProvider.KAKAO, "kakao-original", LocalDateTime.now());
        Profile profile = new Profile(user);
        ReflectionTestUtils.setField(profile, "id", 10L);
        ProfileImage image = new ProfileImage(profile, mock(File.class), (short) 1, true);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(authAccountRepository.findAllByUserId(USER_ID)).willReturn(List.of(account));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.findByProfileIdAndDeletedAtIsNull(10L))
            .willReturn(List.of(image));

        service.withdraw(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getName()).isEqualTo("탈퇴" + USER_ID).hasSize(21);
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1000, 1, 1));
        assertThat(user.getWithdrawnAt()).isNotNull().isEqualTo(user.getUpdatedAt());
        assertThat(account.getProviderUserId()).isEqualTo("withdrawn" + USER_ID);
        assertThat(profile.getDeletedAt()).isEqualTo(user.getWithdrawnAt());
        assertThat(image.getDeletedAt()).isEqualTo(user.getWithdrawnAt());
        verify(userRepository).findByIdForUpdate(USER_ID);
        ArgumentCaptor<LocalDateTime> endedAtCaptor = ArgumentCaptor
            .forClass(LocalDateTime.class);
        InOrder terminationOrder = inOrder(
            likeTerminationService,
            chatRoomWithdrawalTerminationService,
            matchWithdrawalTerminationService);
        terminationOrder.verify(likeTerminationService)
            .terminateForWithdrawal(
                org.mockito.ArgumentMatchers.eq(USER_ID), endedAtCaptor.capture());
        terminationOrder.verify(chatRoomWithdrawalTerminationService)
            .terminateForUser(
                org.mockito.ArgumentMatchers.eq(USER_ID), endedAtCaptor.capture());
        terminationOrder.verify(matchWithdrawalTerminationService)
            .terminateForUser(
                org.mockito.ArgumentMatchers.eq(USER_ID), endedAtCaptor.capture());
        assertThat(endedAtCaptor.getAllValues()).containsOnly(user.getWithdrawnAt());
    }

    @Test
    void 활성_프로필이_없는_사용자도_WITHDRAWN_상태로_변경한다() {
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(authAccountRepository.findAllByUserId(USER_ID)).willReturn(List.of());
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.empty());

        service.withdraw(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        verifyNoInteractions(profileImageRepository);
    }

    @Test
    void 이미_WITHDRAWN인_사용자는_인증계정과_프로필_변경을_처리하지_않는다() {
        User user = user(UserStatus.ACTIVE);
        user.withdraw(LocalDateTime.of(2026, 10, 13, 12, 0));
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

        service.withdraw(USER_ID);

        verifyNoInteractions(
            authAccountRepository,
            profileRepository,
            profileImageRepository,
            likeTerminationService,
            chatRoomWithdrawalTerminationService,
            matchWithdrawalTerminationService);
    }

    @Test
    void 채팅방_종료에_실패하면_Match_종료와_사용자_및_소셜계정_비식별화와_프로필_삭제를_실행하지_않고_예외를_전파한다() {
        User user = user(UserStatus.ACTIVE);
        ChatBusinessException failure = new ChatBusinessException(
            ChatErrorCode.INTERNAL_SERVER_ERROR);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        doThrow(failure).when(chatRoomWithdrawalTerminationService)
            .terminateForUser(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));

        assertThatThrownBy(() -> service.withdraw(USER_ID)).isSameAs(failure);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verifyNoInteractions(
            matchWithdrawalTerminationService,
            authAccountRepository,
            profileRepository,
            profileImageRepository);
    }

    @Test
    void 사용자가_없으면_인증오류를_반환한다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID))
            .isInstanceOf(AuthenticationRequiredException.class);
    }

    private User user(UserStatus status) {
        User user = User.create("기존이름", LocalDate.of(2000, 1, 1), Gender.MALE,
            LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}
