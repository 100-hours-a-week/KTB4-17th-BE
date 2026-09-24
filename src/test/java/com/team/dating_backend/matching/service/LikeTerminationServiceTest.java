package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LikeTerminationServiceTest {

    private UserRepository userRepository;
    private LikeRepository likeRepository;
    private LikeTerminationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        likeRepository = mock(LikeRepository.class);
        service = new LikeTerminationService(userRepository, likeRepository);
        User user = mock(User.class);
        given(userRepository.findById(any())).willReturn(Optional.of(user));
    }

    @Test
    void 탈퇴는_회원이_보내거나_받은_PENDING_좋아요를_WITHDRAWN으로_종료한다() {
        given(likeRepository.resolvePendingForUser(any(), any(), any(), any())).willReturn(3);

        int updated = service.terminateForWithdrawal(7L);

        assertThat(updated).isEqualTo(3);
        verify(userRepository).findById(7L);
        verify(likeRepository)
            .resolvePendingForUser(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(LikeStatus.PENDING),
                org.mockito.ArgumentMatchers.eq(LikeStatus.WITHDRAWN),
                any(LocalDateTime.class));
    }
}
