package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LikeRejectServiceTest {

    private LikeRepository likeRepository;
    private LikeRejectService service;

    @BeforeEach
    void setUp() {
        likeRepository = mock(LikeRepository.class);
        service = new LikeRejectService(likeRepository);
    }

    @Test
    void 좋아요_ID가_양수가_아니면_INVALID_REQUEST가_발생한다() {
        assertThatThrownBy(() -> service.rejectLike(1L, 0L))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void 좋아요가_없으면_LIKE_NOT_FOUND가_발생한다() {
        given(likeRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.rejectLike(1L, 10L))
            .isInstanceOfSatisfying(LikeBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(LikeErrorCode.LIKE_NOT_FOUND));
    }

    @Test
    void 로그인_사용자가_수신자가_아니면_NOT_LIKE_RECEIVER가_발생한다() {
        Like like = new Like(2L, 3L, LocalDateTime.now());
        given(likeRepository.findById(10L)).willReturn(Optional.of(like));

        assertThatThrownBy(() -> service.rejectLike(1L, 10L))
            .isInstanceOfSatisfying(LikeBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(LikeErrorCode.NOT_LIKE_RECEIVER));
        assertThat(like.getStatus()).isEqualTo(LikeStatus.PENDING);
        assertThat(like.getResolvedAt()).isNull();
    }

    @Test
    void 이미_MATCHED인_좋아요는_LIKE_ALREADY_RESOLVED가_발생한다() {
        Like like = resolvedLike(LikeStatus.MATCHED);
        LocalDateTime resolvedAt = like.getResolvedAt();
        given(likeRepository.findById(10L)).willReturn(Optional.of(like));

        assertAlreadyResolved();
        assertThat(like.getStatus()).isEqualTo(LikeStatus.MATCHED);
        assertThat(like.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    void 이미_REJECTED인_좋아요는_LIKE_ALREADY_RESOLVED가_발생한다() {
        Like like = resolvedLike(LikeStatus.REJECTED);
        LocalDateTime resolvedAt = like.getResolvedAt();
        given(likeRepository.findById(10L)).willReturn(Optional.of(like));

        assertAlreadyResolved();
        assertThat(like.getStatus()).isEqualTo(LikeStatus.REJECTED);
        assertThat(like.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    void 받은_PENDING_좋아요를_거절하면_REJECTED가_되고_처리_시각을_기록한다() {
        Like like = new Like(2L, 1L, LocalDateTime.now().minusDays(1));
        given(likeRepository.findById(10L)).willReturn(Optional.of(like));
        LocalDateTime beforeReject = LocalDateTime.now();

        service.rejectLike(1L, 10L);

        assertThat(like.getStatus()).isEqualTo(LikeStatus.REJECTED);
        assertThat(like.getResolvedAt()).isAfterOrEqualTo(beforeReject);
        verify(likeRepository).findById(10L);
    }

    private Like resolvedLike(LikeStatus status) {
        Like like = new Like(2L, 1L, LocalDateTime.now().minusDays(1));
        like.resolveLike(status, LocalDateTime.now().minusHours(1));
        return like;
    }

    private void assertAlreadyResolved() {
        assertThatThrownBy(() -> service.rejectLike(1L, 10L))
            .isInstanceOfSatisfying(LikeBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(LikeErrorCode.LIKE_ALREADY_RESOLVED));
    }
}
