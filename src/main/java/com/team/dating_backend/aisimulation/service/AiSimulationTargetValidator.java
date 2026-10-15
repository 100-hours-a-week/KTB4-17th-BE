package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiSimulationTargetValidator {
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;

    public void validate(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_USER);
        }
        if (!userRepository.existsByIdAndStatusNot(targetUserId, UserStatus.WITHDRAWN)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.TARGET_USER_NOT_FOUND);
        }
        if (userBlockRepository.existsActiveBlockBetween(userId, targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.TARGET_USER_NOT_FOUND);
        }
    }

    public void validateForUpdate(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_USER);
        }
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(AuthenticationRequiredException::new);
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new AuthenticationRequiredException();
        }
        User targetUser = userRepository.findByIdForUpdate(targetUserId)
            .orElseThrow(() -> new AiSimulationBusinessException(
                AiSimulationErrorCode.TARGET_USER_NOT_FOUND));
        if (targetUser.getStatus() == UserStatus.WITHDRAWN
            || userBlockRepository.existsActiveBlockBetween(userId, targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.TARGET_USER_NOT_FOUND);
        }
    }
}
