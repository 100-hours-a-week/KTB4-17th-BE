package com.team.dating_backend.matching.service;

import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchWithdrawalTerminationService {

    private final MatchRepository matchRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateForUser(Long userId, LocalDateTime endedAt) {
        matchRepository.findAllActiveForUserForUpdate(userId)
            .forEach(memberMatch -> memberMatch.end(MatchEndReason.USER_WITHDRAWN, endedAt));
    }
}
