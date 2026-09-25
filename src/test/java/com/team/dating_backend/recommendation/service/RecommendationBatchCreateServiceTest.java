package com.team.dating_backend.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.enums.RecommendationGenerationType;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationCandidateRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationBatchCreateServiceTest {

    private UserRepository userRepository;
    private RecommendationCandidateRepository recommendationCandidateRepository;
    private RecommendationBatchRepository recommendationBatchRepository;
    private RecommendationItemRepository recommendationItemRepository;
    private RecommendationBatchCreateService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recommendationCandidateRepository = mock(RecommendationCandidateRepository.class);
        recommendationBatchRepository = mock(RecommendationBatchRepository.class);
        recommendationItemRepository = mock(RecommendationItemRepository.class);
        service = new RecommendationBatchCreateService(
            userRepository,
            recommendationCandidateRepository,
            recommendationBatchRepository,
            recommendationItemRepository);
        given(recommendationBatchRepository.save(any(RecommendationBatch.class))).willAnswer(
            invocation -> {
                RecommendationBatch batch = invocation.getArgument(0);
                ReflectionTestUtils.setField(batch, "id", 42L);
                return batch;
            });
    }

    @Test
    void 추천_후보를_순서대로_아이템으로_저장하고_배치_정보를_반환한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationCandidateRepository.findEligibleCandidateIds(5L))
            .willReturn(List.of(2L, 9L));

        var response = service.createRecommendationBatch(5L);

        assertThat(response.batchId()).isEqualTo(42L);
        ArgumentCaptor<RecommendationBatch> batchCaptor = ArgumentCaptor.forClass(
            RecommendationBatch.class);
        verify(recommendationBatchRepository).save(batchCaptor.capture());
        RecommendationBatch batch = batchCaptor.getValue();
        assertThat(batch.getUserId()).isEqualTo(5L);
        assertThat(batch.getGenerationType()).isEqualTo(RecommendationGenerationType.DEFAULT);
        assertThat(batch.getDeletedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(batch.getCreatedAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(recommendationItemRepository).saveAll(itemCaptor.capture());
        assertThat(itemCaptor.getValue())
            .extracting(RecommendationItem::getRecommendationBatchId)
            .containsExactly(42L, 42L);
        assertThat(itemCaptor.getValue())
            .extracting(RecommendationItem::getCandidateUserId)
            .containsExactly(2L, 9L);
        assertThat(itemCaptor.getValue())
            .extracting(RecommendationItem::getRankingOrder)
            .containsExactly(1, 2);
        InOrder saveOrder = inOrder(recommendationBatchRepository, recommendationItemRepository);
        saveOrder.verify(recommendationBatchRepository).save(any(RecommendationBatch.class));
        saveOrder.verify(recommendationItemRepository).saveAll(any());
        saveOrder.verify(recommendationBatchRepository).markPreviousBatchesDeleted(
            5L, 42L, batch.getCreatedAt());
    }

    @Test
    void 후보가_없어도_빈_배치를_생성한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationCandidateRepository.findEligibleCandidateIds(5L))
            .willReturn(List.of());

        assertThat(service.createRecommendationBatch(5L).batchId()).isEqualTo(42L);

        verify(recommendationBatchRepository).save(any(RecommendationBatch.class));
        verify(recommendationItemRepository).saveAll(List.of());
        verify(recommendationBatchRepository).markPreviousBatchesDeleted(
            eq(5L), eq(42L), any());
    }

    @Test
    void 아이템_저장에_실패하면_이전_배치를_삭제_처리하지_않는다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationCandidateRepository.findEligibleCandidateIds(5L))
            .willReturn(List.of(2L));
        given(recommendationItemRepository.saveAll(any())).willThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.createRecommendationBatch(5L))
            .isInstanceOf(IllegalStateException.class);

        verify(recommendationBatchRepository, never()).markPreviousBatchesDeleted(any(), any(), any());
    }

    @Test
    void 비활성_회원과_존재하지_않는_회원은_배치를_만들지_못한다() {
        for (UserStatus status : new UserStatus[]{
            UserStatus.ONBOARDING, UserStatus.SUSPENDED, UserStatus.WITHDRAWN
        }) {
            User requester = user(status);
            given(userRepository.findById(5L)).willReturn(Optional.of(requester));
            assertThatThrownBy(() -> service.createRecommendationBatch(5L))
                .isInstanceOf(RecommendationBusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }
        given(userRepository.findById(5L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.createRecommendationBatch(5L))
            .isInstanceOf(RecommendationBusinessException.class)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(
            recommendationCandidateRepository, recommendationItemRepository);
    }

    private User user(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        return user;
    }
}
