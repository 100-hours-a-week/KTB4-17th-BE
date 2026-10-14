package com.team.dating_backend.matching.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MatchTest {

    @Test
    void 이미_종료된_Match는_최초_종료_정보를_유지한다() {
        LocalDateTime matchedAt = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime firstEndedAt = matchedAt.plusHours(1);
        Match userMatch = new Match(1L, 2L, matchedAt);

        userMatch.end(MatchEndReason.BLOCKED, firstEndedAt);
        userMatch.end(MatchEndReason.BLOCKED, firstEndedAt.plusHours(1));

        assertThat(userMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(userMatch.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(userMatch.getEndedAt()).isEqualTo(firstEndedAt);
    }
}
