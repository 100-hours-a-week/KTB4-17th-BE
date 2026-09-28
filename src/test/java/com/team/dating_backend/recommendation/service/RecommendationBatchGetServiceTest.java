package com.team.dating_backend.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationBatchGetServiceTest {

    private UserRepository userRepository;
    private RecommendationBatchRepository recommendationBatchRepository;
    private RecommendationBatchGetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recommendationBatchRepository = mock(RecommendationBatchRepository.class);
        service = new RecommendationBatchGetService(userRepository, recommendationBatchRepository);
    }

    @Test
    void 사용자의_활성_배치가_있으면_배치_ID를_반환한다() {
        User requester = user(UserStatus.ACTIVE);
        RecommendationBatch batch = mock(RecommendationBatch.class);
        given(batch.getId()).willReturn(23L);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationBatchRepository.findByUserIdAndDeletedAtIsNull(5L))
            .willReturn(Optional.of(batch));

        assertThat(service.getActiveRecommendationBatch(5L).batchId()).isEqualTo(23L);

        verify(userRepository).findById(5L);
        verify(recommendationBatchRepository).findByUserIdAndDeletedAtIsNull(5L);
    }

    @Test
    void 활성_배치가_없으면_배치_ID로_null을_반환한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationBatchRepository.findByUserIdAndDeletedAtIsNull(5L))
            .willReturn(Optional.empty());

        assertThat(service.getActiveRecommendationBatch(5L).batchId()).isNull();

        verify(recommendationBatchRepository).findByUserIdAndDeletedAtIsNull(5L);
    }

    @Test
    void 비활성_회원은_배치를_조회할_수_없다() {
        for (UserStatus status : new UserStatus[]{
            UserStatus.ONBOARDING, UserStatus.SUSPENDED, UserStatus.WITHDRAWN
        }) {
            User requester = user(status);
            given(userRepository.findById(5L)).willReturn(Optional.of(requester));
            assertThatThrownBy(() -> service.getActiveRecommendationBatch(5L))
                .isInstanceOf(RecommendationBusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }
        given(userRepository.findById(5L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.getActiveRecommendationBatch(5L))
            .isInstanceOf(RecommendationBusinessException.class)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
    }

    private User user(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        return user;
    }
}
