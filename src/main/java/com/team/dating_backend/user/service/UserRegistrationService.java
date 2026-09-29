package com.team.dating_backend.user.service;

import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.dto.PendingRegistrationTokenPayload;
import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.exception.PendingRegistrationAccessDeniedException;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.user.dto.request.UserRegistrationRequest;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.exception.RegistrationAgeRequirementNotMetException;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    private static final int MIN_REGISTRATION_AGE = 19;
    private static final int MAX_REGISTRATION_AGE_EXCLUSIVE = 40;
    private static final ZoneId REGISTRATION_AGE_ZONE = ZoneId.of("Asia/Seoul");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;

    @Transactional
    public IssuedAuthTokens registerUser(
        String pendingRegistrationToken, UserRegistrationRequest request) {
        PendingRegistrationTokenPayload payload = jwtService
            .parsePendingRegistrationToken(pendingRegistrationToken);

        UserAuthAccount existingAccount = userAuthAccountRepository
            .findForUpdateByProviderAndProviderUserId(
                payload.provider(), payload.providerUserId())
            .orElse(null);

        if (existingAccount != null
            && existingAccount.getUser().getStatus() != UserStatus.WITHDRAWN) {
            throw new PendingRegistrationAccessDeniedException();
        }

        validateRegistrationAge(request.birthDate());

        LocalDateTime now = LocalDateTime.now();

        User user = User.create(request.name(), request.birthDate(), request.gender(), now);

        User savedUser = userRepository.save(user);

        if (existingAccount == null) {
            UserAuthAccount userAuthAccount = UserAuthAccount.create(
                savedUser, payload.provider(), payload.providerUserId(), now);

            userAuthAccountRepository.save(userAuthAccount);
        } else {
            existingAccount.relink(savedUser, now);
            userAuthAccountRepository.save(existingAccount);
        }

        return new IssuedAuthTokens(
            jwtService.createServiceAuthToken(savedUser.getId()),
            jwtService.createRefreshAuthToken(savedUser.getId()));
    }

    private void validateRegistrationAge(LocalDate birthDate) {
        LocalDate today = LocalDate.now(REGISTRATION_AGE_ZONE);
        int age = Period.between(birthDate, today).getYears();

        if (age < MIN_REGISTRATION_AGE || age >= MAX_REGISTRATION_AGE_EXCLUSIVE) {
            throw new RegistrationAgeRequirementNotMetException();
        }
    }
}
