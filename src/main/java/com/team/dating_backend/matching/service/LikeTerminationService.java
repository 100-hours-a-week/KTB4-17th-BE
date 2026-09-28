package com.team.dating_backend.matching.service;

import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeTerminationService {

    private final UserRepository userRepository;
    private final LikeRepository likeRepository;

    @Transactional
    public int terminateForWithdrawal(Long userId) {
        userRepository
            .findById(userId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
        return likeRepository.resolvePendingForUser(
            userId, LikeStatus.PENDING, LikeStatus.WITHDRAWN, LocalDateTime.now());
    }
}
