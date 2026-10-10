package com.team.dating_backend.user.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.dto.response.UserBlockResponse;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.UserBlockErrorCode;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.exception.UserBlockBusinessException;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBlockService {

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;

    @Transactional
    public UserBlockResult block(Long blockerUserId, Long targetUserId) {
        User blocker = requireAuthenticatedUser(blockerUserId);
        if (blocker.getStatus() != UserStatus.ACTIVE) {
            throw new UserBlockBusinessException(UserBlockErrorCode.BLOCK_ACCESS_DENIED);
        }
        if (blockerUserId.equals(targetUserId)) {
            throw new UserBlockBusinessException(UserBlockErrorCode.SELF_BLOCK_NOT_ALLOWED);
        }
        validateTargetUserId(targetUserId);

        User target = userRepository.findById(targetUserId)
            .filter(user -> user.getStatus() == UserStatus.ACTIVE
                || user.getStatus() == UserStatus.SUSPENDED)
            .orElseThrow(
                () -> new UserBlockBusinessException(UserBlockErrorCode.USER_NOT_AVAILABLE));

        UserBlock activeBlock = userBlockRepository
            .findByBlockerUserIdAndBlockedUserIdAndUnblockedAtIsNull(
                blocker.getId(), target.getId())
            .orElse(null);
        boolean created = activeBlock == null;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (created) {
            activeBlock = userBlockRepository.save(
                new UserBlock(blocker.getId(), target.getId(), now));
        }

        return new UserBlockResult(
            new UserBlockResponse(target.getId(), activeBlock.getBlockedAt()),
            created);
    }

    private User requireAuthenticatedUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UserBlockBusinessException(CommonErrorCode.AUTH_REQUIRED);
        }
        return userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new UserBlockBusinessException(CommonErrorCode.AUTH_REQUIRED));
    }

    private void validateTargetUserId(Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0) {
            throw new RequestValidationException();
        }
    }
}
