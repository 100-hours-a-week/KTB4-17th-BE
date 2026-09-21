package com.team.dating_backend.user.service;

import com.team.dating_backend.auth.dto.PendingOnboardingTokenPayload;
import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.exception.PendingOnboardingAccessDeniedException;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.user.dto.request.OnboardingIdentityRequest;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;

    @Transactional
    public String saveIdentity(String pendingOnboardingToken, OnboardingIdentityRequest request) {
        PendingOnboardingTokenPayload payload =
                jwtService.parsePendingToken(pendingOnboardingToken);

        UserAuthAccount existingAccount =
                userAuthAccountRepository
                        .findForUpdateByProviderAndProviderUserId(
                                payload.provider(), payload.providerUserId())
                        .orElse(null);

        if (existingAccount != null
                && existingAccount.getUser().getStatus() != UserStatus.WITHDRAWN) {
            throw new PendingOnboardingAccessDeniedException();
        }

        LocalDateTime now = LocalDateTime.now();

        User user = User.create(request.name(), request.birthDate(), request.gender(), now);

        User savedUser = userRepository.save(user);

        if (existingAccount == null) {
            UserAuthAccount userAuthAccount =
                    UserAuthAccount.create(
                            savedUser, payload.provider(), payload.providerUserId(), now);

            userAuthAccountRepository.save(userAuthAccount);
        } else {
            existingAccount.relink(savedUser, now);
            userAuthAccountRepository.save(existingAccount);
        }

        return jwtService.createServiceAuthToken(savedUser.getId());
    }
}
