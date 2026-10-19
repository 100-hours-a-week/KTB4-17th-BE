package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.FaceVerificationStatus;
import com.team.dating_backend.user.enums.UserStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final UserAuthAccountRepository userAuthAccountRepository;

    @Transactional
    public SocialLoginResult login(
        AuthProvider provider, String providerUserId, String kakaoTalkNickname) {
        return userAuthAccountRepository
            .findByProviderAndProviderUserId(provider, providerUserId)
            .<SocialLoginResult>map(
                account -> resolveExistingUser(
                    account.getUser(), provider, providerUserId, kakaoTalkNickname))
            .orElseGet(
                () -> new SocialLoginResult.PendingRegistration(
                    provider, providerUserId, kakaoTalkNickname));
    }

    private SocialLoginResult resolveExistingUser(
        User user,
        AuthProvider provider,
        String providerUserId,
        String kakaoTalkNickname) {
        if (user.getStatus() == UserStatus.ACTIVE) {
            user.updateName(kakaoTalkNickname, LocalDateTime.now());
            LoginDestination destination = user.getFaceVerificationStatus() == FaceVerificationStatus.VERIFIED
                ? LoginDestination.SERVICE
                : LoginDestination.ONBOARDING;
            return new SocialLoginResult.Authenticated(user.getId(), destination);
        }

        if (user.getStatus() == UserStatus.ONBOARDING) {
            user.updateName(kakaoTalkNickname, LocalDateTime.now());
            return new SocialLoginResult.Authenticated(user.getId(), LoginDestination.ONBOARDING);
        }

        if (user.getStatus() == UserStatus.WITHDRAWN) {
            return new SocialLoginResult.PendingRegistration(
                provider, providerUserId, kakaoTalkNickname);
        }

        throw new IllegalStateException(
            AuthErrorCode.SOCIAL_LOGIN_USER_STATUS_NOT_SUPPORTED.name());
    }
}
