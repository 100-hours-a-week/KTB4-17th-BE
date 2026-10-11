package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
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
            throw new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_MEMBER);
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.TARGET_MEMBER_NOT_FOUND);
        }
        if (userBlockRepository.existsActiveBlockBetween(userId, targetUserId)) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.TARGET_MEMBER_NOT_FOUND);
        }
    }
}
