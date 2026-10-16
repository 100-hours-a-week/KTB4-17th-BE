package com.team.dating_backend.matching.service;

import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeTerminationService {

    private final LikeRepository likeRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public int terminateForWithdrawal(Long userId, LocalDateTime endedAt) {
        return likeRepository.resolvePendingForUser(
            userId, LikeStatus.PENDING, LikeStatus.WITHDRAWN, endedAt);
    }
}
