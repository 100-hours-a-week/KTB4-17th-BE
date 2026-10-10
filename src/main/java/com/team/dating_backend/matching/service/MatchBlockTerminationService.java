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
public class MatchBlockTerminationService {

    private final MatchRepository matchRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateBetween(Long firstUserId, Long secondUserId, LocalDateTime endedAt) {
        matchRepository.findActiveBetweenForUpdate(firstUserId, secondUserId)
            .ifPresent(memberMatch -> memberMatch.end(MatchEndReason.BLOCKED, endedAt));
    }
}
