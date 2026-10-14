package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchWithdrawalTerminationServiceTest {

    private MatchRepository matchRepository;
    private MatchWithdrawalTerminationService service;

    @BeforeEach
    void setUp() {
        matchRepository = mock(MatchRepository.class);
        service = new MatchWithdrawalTerminationService(matchRepository);
    }

    @Test
    void 사용자의_모든_활성_Match를_USER_WITHDRAWN으로_종료한다() {
        LocalDateTime matchedAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 14, 12, 0);
        Match first = new Match(1L, 2L, matchedAt);
        Match second = new Match(3L, 1L, matchedAt);
        given(matchRepository.findAllActiveForUserForUpdate(1L))
            .willReturn(List.of(first, second));

        service.terminateForUser(1L, endedAt);

        assertThat(List.of(first, second)).allSatisfy(memberMatch -> {
            assertThat(memberMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
            assertThat(memberMatch.getEndReason()).isEqualTo(MatchEndReason.USER_WITHDRAWN);
            assertThat(memberMatch.getEndedAt()).isEqualTo(endedAt);
        });
        verify(matchRepository).findAllActiveForUserForUpdate(1L);
    }

    @Test
    void 이미_종료된_Match의_최초_종료_정보는_유지한다() {
        LocalDateTime firstEndedAt = LocalDateTime.of(2026, 10, 13, 12, 0);
        Match ended = new Match(1L, 2L, firstEndedAt.minusDays(1));
        ended.end(MatchEndReason.BLOCKED, firstEndedAt);
        given(matchRepository.findAllActiveForUserForUpdate(1L)).willReturn(List.of(ended));

        service.terminateForUser(1L, firstEndedAt.plusDays(1));

        assertThat(ended.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(ended.getEndedAt()).isEqualTo(firstEndedAt);
    }
}
