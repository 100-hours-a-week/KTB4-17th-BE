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

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.enums.RecommendationGenerationType;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationCandidateRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.repository.RecommendationPreferenceRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private RecommendationPreferenceRepository recommendationPreferenceRepository;
    private RecommendationBatchCreateService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recommendationCandidateRepository = mock(RecommendationCandidateRepository.class);
        recommendationBatchRepository = mock(RecommendationBatchRepository.class);
        recommendationItemRepository = mock(RecommendationItemRepository.class);
        recommendationPreferenceRepository = mock(RecommendationPreferenceRepository.class);
        service = new RecommendationBatchCreateService(
            userRepository,
            recommendationCandidateRepository,
            recommendationBatchRepository,
            recommendationItemRepository,
            recommendationPreferenceRepository);
        given(recommendationBatchRepository.save(any(RecommendationBatch.class))).willAnswer(
            invocation -> {
                RecommendationBatch batch = invocation.getArgument(0);
                ReflectionTestUtils.setField(batch, "id", 42L);
                return batch;
            });
    }

    @Test
    void 최초_후보를_저장하고_조회용_ID는_유지한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        givenCandidateIds(List.of(2L, 9L, 11L));

        var response = service.createRecommendationBatch(5L).orElseThrow();

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
            .containsOnly(42L);
        assertThat(itemCaptor.getValue())
            .extracting(RecommendationItem::getCandidateUserId)
            .containsExactlyInAnyOrder(2L, 9L, 11L)
            .doesNotHaveDuplicates();
        assertThat(itemCaptor.getValue())
            .extracting(RecommendationItem::getRankingOrder)
            .containsExactly(1, 2, 3)
            .doesNotHaveDuplicates();
        InOrder saveOrder = inOrder(recommendationBatchRepository, recommendationItemRepository);
        saveOrder.verify(recommendationBatchRepository).save(any(RecommendationBatch.class));
        saveOrder.verify(recommendationItemRepository).saveAll(any());
    }

    @Test
    void 후보가_없어도_빈_목록을_생성해_재요청에_재사용한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        givenCandidateIds(List.of());

        assertThat(service.createRecommendationBatch(5L).orElseThrow().batchId()).isEqualTo(42L);

        verify(recommendationBatchRepository).save(any(RecommendationBatch.class));
        verifyNoInteractions(recommendationItemRepository);
    }

    @Test
    void 최초_아이템_저장_실패를_호출자에게_전달한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        givenCandidateIds(List.of(2L));
        given(recommendationItemRepository.saveAll(any())).willThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.createRecommendationBatch(5L))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 반복_생성_요청은_후보를_재계산하지_않고_기존_목록을_반환한다() {
        RecommendationBatch batch = existingBatch();

        assertThat(service.createRecommendationBatch(5L).orElseThrow().batchId()).isEqualTo(42L);

        verifyNoInteractions(recommendationCandidateRepository, recommendationPreferenceRepository,
            recommendationItemRepository);
        verify(recommendationBatchRepository, never()).save(any());
    }

    @Test
    void 순서만_다른_동일_후보는_Item을_쓰지_않는다() {
        existingBatch();
        givenCandidateIds(List.of(9L, 2L));
        given(recommendationItemRepository.findAllByRecommendationBatchId(42L))
            .willReturn(List.of(item(101L, 2L, 1), item(102L, 9L, 2)));

        service.refreshRecommendationBatch(5L);

        verify(recommendationBatchRepository, never()).save(any());
        verify(recommendationItemRepository, never()).saveAll(any());
        verify(recommendationItemRepository, never()).deleteItems(any(), any());
    }

    @Test
    void 유지되는_Item을_건드리지_않고_추가와_제외만_반영한다() {
        existingBatch();
        givenCandidateIds(List.of(9L, 11L));
        given(recommendationItemRepository.findAllByRecommendationBatchId(42L))
            .willReturn(List.of(item(101L, 2L, 1), item(102L, 9L, 2)));

        service.refreshRecommendationBatch(5L);

        verify(recommendationItemRepository).deleteItems(42L, List.of(101L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationItem>> added = ArgumentCaptor.forClass(List.class);
        verify(recommendationItemRepository).saveAll(added.capture());
        assertThat(added.getValue()).extracting(RecommendationItem::getCandidateUserId)
            .containsExactly(11L);
        assertThat(added.getValue().getFirst().getRankingOrder()).isEqualTo(3);
        verify(recommendationBatchRepository, never()).save(any());
    }

    @Test
    void 후보가_모두_제외되어도_목록_ID를_유지한다() {
        existingBatch();
        givenCandidateIds(List.of());
        given(recommendationItemRepository.findAllByRecommendationBatchId(42L))
            .willReturn(List.of(item(101L, 2L, 1)));

        assertThat(service.refreshRecommendationBatch(5L).orElseThrow().batchId()).isEqualTo(42L);

        verify(recommendationItemRepository).deleteItems(42L, List.of(101L));
        verify(recommendationItemRepository, never()).saveAll(any());
        verify(recommendationBatchRepository, never()).save(any());
    }

    private RecommendationBatch existingBatch() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        RecommendationBatch batch = new RecommendationBatch(5L, LocalDateTime.now());
        ReflectionTestUtils.setField(batch, "id", 42L);
        given(recommendationBatchRepository.findByUserIdAndDeletedAtIsNull(5L))
            .willReturn(Optional.of(batch));
        return batch;
    }

    private RecommendationItem item(long id, long candidateId, int ranking) {
        RecommendationItem item = new RecommendationItem(42L, candidateId, ranking);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    void 비활성_회원과_존재하지_않는_회원은_배치를_만들지_못한다() {
        for (UserStatus status : new UserStatus[]{
            UserStatus.ONBOARDING, UserStatus.SUSPENDED, UserStatus.WITHDRAWN
        }) {
            User requester = user(status);
            given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
            assertThatThrownBy(() -> service.createRecommendationBatch(5L))
                .isInstanceOf(RecommendationBusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.createRecommendationBatch(5L))
            .isInstanceOf(RecommendationBusinessException.class)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(
            recommendationCandidateRepository, recommendationItemRepository, recommendationPreferenceRepository);
    }

    private User user(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        given(user.getGender()).willReturn(Gender.MALE);
        return user;
    }

    private void givenCandidateIds(List<Long> ids) {
        given(recommendationCandidateRepository.findEligibleCandidateIds(
            5L, Gender.FEMALE, null, null, null, null,
            true, List.of(Religion.values()), true, List.of(Drinking.values()),
            true, List.of(Smoking.values())))
            .willReturn(ids);
    }

    @Test
    void 저장된_선호조건과_선택_성별과_만_나이_날짜_경계를_후보_조회에_전달한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        List<Gender> preferredGenders = List.of(Gender.MALE, Gender.FEMALE);
        RecommendationPreference preference = new RecommendationPreference(requester,
            (short) 25, (short) 30, (short) 160, (short) 180,
            List.of(Religion.NONE, Religion.CATHOLIC), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), preferredGenders, LocalDateTime.now());
        given(recommendationPreferenceRepository.findById(5L)).willReturn(Optional.of(preference));
        given(recommendationCandidateRepository.findEligibleCandidateIdsByGenders(
            eq(5L), eq(preferredGenders), any(LocalDate.class), any(LocalDate.class),
            eq((short) 160), eq((short) 180), eq(false), eq(List.of(Religion.NONE, Religion.CATHOLIC)),
            eq(false), eq(List.of(Drinking.NEVER)), eq(false), eq(List.of(Smoking.NON_SMOKER))))
            .willReturn(List.of(2L));
        LocalDate before = LocalDate.now();

        service.createRecommendationBatch(5L);

        ArgumentCaptor<LocalDate> lowerBound = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> upperBound = ArgumentCaptor.forClass(LocalDate.class);
        verify(recommendationCandidateRepository).findEligibleCandidateIdsByGenders(
            eq(5L), eq(preferredGenders), lowerBound.capture(), upperBound.capture(),
            eq((short) 160), eq((short) 180), eq(false), eq(List.of(Religion.NONE, Religion.CATHOLIC)),
            eq(false), eq(List.of(Drinking.NEVER)), eq(false), eq(List.of(Smoking.NON_SMOKER)));
        LocalDate after = LocalDate.now();
        assertThat(lowerBound.getValue()).isIn(before.minusYears(31), after.minusYears(31));
        assertThat(upperBound.getValue()).isIn(before.minusYears(25), after.minusYears(25));
    }

    @Test
    void 실제_선호_필터가_있으면_PREFERENCE_유형으로_저장한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        RecommendationPreference preference = new RecommendationPreference(requester,
            List.of(Religion.NONE), List.of(), List.of(), LocalDateTime.now());
        given(recommendationPreferenceRepository.findById(5L)).willReturn(Optional.of(preference));
        given(recommendationCandidateRepository.findEligibleCandidateIds(
            5L, Gender.FEMALE, null, null, null, null,
            false, List.of(Religion.NONE), true, List.of(Drinking.values()),
            true, List.of(Smoking.values())))
            .willReturn(List.of(2L));

        service.createRecommendationBatch(5L);

        ArgumentCaptor<RecommendationBatch> captor = ArgumentCaptor.forClass(RecommendationBatch.class);
        verify(recommendationBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getGenerationType()).isEqualTo(RecommendationGenerationType.PREFERENCE);
    }

    @Test
    void 저장된_선호조건이_모두_미설정이면_DEFAULT_유형으로_저장한다() {
        User requester = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(5L)).willReturn(Optional.of(requester));
        RecommendationPreference preference = new RecommendationPreference(requester, LocalDateTime.now());
        given(recommendationPreferenceRepository.findById(5L)).willReturn(Optional.of(preference));
        givenCandidateIds(List.of(2L));

        service.createRecommendationBatch(5L);

        ArgumentCaptor<RecommendationBatch> captor = ArgumentCaptor.forClass(RecommendationBatch.class);
        verify(recommendationBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getGenerationType()).isEqualTo(RecommendationGenerationType.DEFAULT);
    }
}
