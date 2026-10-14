package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LikeTerminationServiceTest {

    private LikeRepository likeRepository;
    private LikeTerminationService service;

    @BeforeEach
    void setUp() {
        likeRepository = mock(LikeRepository.class);
        service = new LikeTerminationService(likeRepository);
    }

    @Test
    void 탈퇴는_회원이_보내거나_받은_PENDING_좋아요를_WITHDRAWN으로_종료한다() {
        given(likeRepository.resolvePendingForUser(any(), any(), any(), any())).willReturn(3);
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 14, 12, 0);

        int updated = service.terminateForWithdrawal(7L, endedAt);

        assertThat(updated).isEqualTo(3);
        verify(likeRepository)
            .resolvePendingForUser(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(LikeStatus.PENDING),
                org.mockito.ArgumentMatchers.eq(LikeStatus.WITHDRAWN),
                org.mockito.ArgumentMatchers.eq(endedAt));
    }
}
