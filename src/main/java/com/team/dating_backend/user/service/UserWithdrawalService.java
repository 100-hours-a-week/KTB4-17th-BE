package com.team.dating_backend.user.service;

import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.aipractice.service.AiPracticeWithdrawalTerminationService;
import com.team.dating_backend.aisimulation.service.AiSimulationWithdrawalTerminationService;
import com.team.dating_backend.chat.service.ChatRoomWithdrawalTerminationService;
import com.team.dating_backend.matching.service.LikeTerminationService;
import com.team.dating_backend.matching.service.MatchWithdrawalTerminationService;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;
    private final ProfileRepository profileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final LikeTerminationService likeTerminationService;
    private final ChatRoomWithdrawalTerminationService chatRoomWithdrawalTerminationService;
    private final MatchWithdrawalTerminationService matchWithdrawalTerminationService;
    private final AiPracticeWithdrawalTerminationService aiPracticeWithdrawalTerminationService;
    private final AiSimulationWithdrawalTerminationService aiSimulationWithdrawalTerminationService;

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(AuthenticationRequiredException::new);
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            return;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        likeTerminationService.terminateForWithdrawal(userId, now);
        matchWithdrawalTerminationService.terminateForUser(userId, now);
        chatRoomWithdrawalTerminationService.terminateForUser(userId, now);
        aiPracticeWithdrawalTerminationService.terminateForUser(userId, now);
        aiSimulationWithdrawalTerminationService.terminateForUser(userId, now);
        for (UserAuthAccount account : userAuthAccountRepository.findAllByUserId(userId)) {
            account.anonymize(userId);
        }
        profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .ifPresent(profile -> deleteProfile(profile, now));
        user.withdraw(now);
    }

    private void deleteProfile(Profile profile, LocalDateTime now) {
        profileImageRepository.findByProfileIdAndDeletedAtIsNull(profile.getId())
            .forEach(image -> image.markDeleted(now));
        profile.markDeleted(now);
    }
}
