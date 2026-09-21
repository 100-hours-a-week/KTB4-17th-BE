package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final UserAuthAccountRepository userAuthAccountRepository;

    @Transactional(readOnly = true)
    public SocialLoginResult login(AuthProvider provider, String providerUserId) {
        return userAuthAccountRepository
                .findByProviderAndProviderUserId(provider, providerUserId)
                .<SocialLoginResult>map(
                        account -> resolveExistingUser(account.getUser(), provider, providerUserId))
                .orElseGet(() -> new SocialLoginResult.PendingOnboarding(provider, providerUserId));
    }

    private SocialLoginResult resolveExistingUser(
            User user, AuthProvider provider, String providerUserId) {
        if (user.getStatus() == UserStatus.ACTIVE) {
            return new SocialLoginResult.Authenticated(user.getId(), LoginDestination.SERVICE);
        }

        if (user.getStatus() == UserStatus.ONBOARDING) {
            return new SocialLoginResult.Authenticated(user.getId(), LoginDestination.ONBOARDING);
        }

        if (user.getStatus() == UserStatus.WITHDRAWN) {
            return new SocialLoginResult.PendingOnboarding(provider, providerUserId);
        }

        throw new IllegalStateException("Unsupported user status for social login");
    }
}
