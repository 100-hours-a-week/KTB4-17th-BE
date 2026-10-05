package com.team.dating_backend.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemsGetResponse;
import com.team.dating_backend.recommendation.repository.RecommendationItemCandidateRow;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class RecommendationItemGetServiceTest {

    private UserRepository userRepository;
    private RecommendationBatchRepository recommendationBatchRepository;
    private RecommendationItemRepository recommendationItemRepository;
    private ProfileImageGetService profileImageGetService;
    private RecommendationItemGetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recommendationBatchRepository = mock(RecommendationBatchRepository.class);
        recommendationItemRepository = mock(RecommendationItemRepository.class);
        profileImageGetService = mock(ProfileImageGetService.class);
        given(profileImageGetService.getProfileImagesByMemberIds(anyCollection()))
            .willReturn(Map.of());
        service = new RecommendationItemGetService(
            userRepository,
            recommendationBatchRepository,
            recommendationItemRepository,
            profileImageGetService);
    }

    @Test
    void 첫_조회에서_추천_후보_20개와_다음_커서를_반환한다() {
        givenActiveRequesterAndBatch();
        List<RecommendationItemCandidateRow> rows = LongStream.range(0, 21)
            .mapToObj(index -> row(101L + index, 21L + index))
            .toList();
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, null, null, PageRequest.of(0, 21)))
            .willReturn(rows);
        given(profileImageGetService.getProfileImagesByMemberIds(
            LongStream.rangeClosed(21L, 40L).boxed().toList()))
            .willReturn(Map.of(
                21L,
                List.of(
                    new ProfileImageAccessResult(
                        701L,
                        (short) 1,
                        "https://example.com/701",
                        Instant.parse("2026-09-27T12:00:00Z")),
                    new ProfileImageAccessResult(
                        704L,
                        (short) 2,
                        "https://example.com/704",
                        Instant.parse("2026-09-27T12:00:00Z")))));

        RecommendationItemsGetResponse response = service.getRecommendationItems(5L, 42L, null);

        assertThat(response.items()).hasSize(20);
        assertThat(response.items().getFirst().itemId()).isEqualTo(101L);
        assertThat(response.items().getLast().itemId()).isEqualTo(120L);
        assertThat(response.items().getFirst().candidate().memberId()).isEqualTo(21L);
        assertThat(response.items().getFirst().candidate().nickname()).isEqualTo("하리");
        assertThat(response.items().getFirst().candidate().age())
            .isEqualTo(29);
        assertThat(response.items().getFirst().candidate().job()).isEqualTo("개발자");
        assertThat(response.items().getFirst().candidate().region()).isEqualTo("서울특별시 강남구");
        assertThat(response.items().getFirst().candidate().mbti()).isEqualTo(Mbti.INFP);
        assertThat(response.items().getFirst().candidate().images())
            .extracting("fileId", "displayOrder", "imageUrl", "expiresAt")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    701L,
                    (short) 1,
                    "https://example.com/701",
                    Instant.parse("2026-09-27T12:00:00Z")),
                org.assertj.core.groups.Tuple.tuple(
                    704L,
                    (short) 2,
                    "https://example.com/704",
                    Instant.parse("2026-09-27T12:00:00Z")));
        assertThat(response.pageInfo().nextCursor()).isEqualTo(120L);
        assertThat(response.pageInfo().hasNext()).isTrue();
        assertThat(response.pageInfo().batchId()).isEqualTo(42L);
    }

    @Test
    void 커서가_있는_페이지는_해당_Item의_순위와_ID_이후부터_조회한다() {
        givenActiveRequesterAndBatch();
        RecommendationItem cursorItem = mock(RecommendationItem.class);
        given(cursorItem.getRankingOrder()).willReturn(3);
        given(recommendationItemRepository.findByIdAndRecommendationBatchId(101L, 42L))
            .willReturn(Optional.of(cursorItem));
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, 3, 101L, PageRequest.of(0, 21)))
            .willReturn(List.of(row(102L, 22L)));

        RecommendationItemsGetResponse response = service.getRecommendationItems(5L, 42L, 101L);

        assertThat(response.items()).extracting(item -> item.itemId()).containsExactly(102L);
        assertThat(response.items().getFirst().candidate().images()).isEmpty();
        assertThat(response.pageInfo().nextCursor()).isNull();
        assertThat(response.pageInfo().hasNext()).isFalse();
    }

    @Test
    void 페이지_조회_시_추천_후보가_중복되거나_누락되지_않는다() {
        givenActiveRequesterAndBatch();
        List<RecommendationItemCandidateRow> firstQueryRows = LongStream.range(0, 21)
            .mapToObj(index -> row(101L + index, 41L + index))
            .toList();
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, null, null, PageRequest.of(0, 21)))
            .willReturn(firstQueryRows);
        RecommendationItem cursorItem = mock(RecommendationItem.class);
        given(cursorItem.getRankingOrder()).willReturn(20);
        given(recommendationItemRepository.findByIdAndRecommendationBatchId(120L, 42L))
            .willReturn(Optional.of(cursorItem));
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, 20, 120L, PageRequest.of(0, 21)))
            .willReturn(List.of(row(121L, 61L)));

        RecommendationItemsGetResponse firstPage = service.getRecommendationItems(
            5L, 42L, null);
        RecommendationItemsGetResponse secondPage = service.getRecommendationItems(
            5L, 42L, firstPage.pageInfo().nextCursor());

        assertThat(firstPage.items()).hasSize(20);
        assertThat(firstPage.pageInfo().nextCursor()).isEqualTo(120L);
        assertThat(secondPage.pageInfo().nextCursor()).isNull();
        assertThat(Stream.concat(firstPage.items().stream(), secondPage.items().stream())
            .map(item -> item.candidate().memberId()))
            .containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(41L, 61L).boxed().toList())
            .doesNotHaveDuplicates();
    }

    @Test
    void 남은_적격_후보가_없으면_빈_목록을_반환한다() {
        givenActiveRequesterAndBatch();
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, null, null, PageRequest.of(0, 21)))
            .willReturn(List.of());

        RecommendationItemsGetResponse response = service.getRecommendationItems(5L, 42L, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.pageInfo().nextCursor()).isNull();
        assertThat(response.pageInfo().hasNext()).isFalse();
        verify(profileImageGetService).getProfileImagesByMemberIds(List.of());
    }

    @Test
    void 생일이_지나지_않은_후보는_만_나이에서_일년을_제외한다() {
        givenActiveRequesterAndBatch();
        given(recommendationItemRepository.findEligibleItems(
            42L, 5L, null, null, PageRequest.of(0, 21)))
            .willReturn(List.of(new RecommendationItemCandidateRow(
                101L, 21L, LocalDate.now().minusYears(30).plusDays(1),
                "하리", "개발자", Mbti.INFP, "서울특별시", "강남구")));

        RecommendationItemsGetResponse response = service.getRecommendationItems(5L, 42L, null);

        assertThat(response.items().getFirst().candidate().age()).isEqualTo(29);
    }

    @Test
    void 비활성_사용자는_조회할_수_없다() {
        User requester = mock(User.class);
        given(requester.getStatus()).willReturn(UserStatus.SUSPENDED);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));

        assertThatThrownBy(() -> service.getRecommendationItems(5L, 42L, null))
            .isInstanceOf(RecommendationBusinessException.class)
            .satisfies(exception -> assertThat(
                ((RecommendationBusinessException) exception).getErrorCode())
                .isEqualTo(RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        verifyNoInteractions(recommendationItemRepository);
    }

    @Test
    void 타인의_배치나_삭제된_배치는_조회할_수_없다() {
        User requester = mock(User.class);
        given(requester.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        RecommendationBatch otherBatch = mock(RecommendationBatch.class);
        given(otherBatch.getUserId()).willReturn(6L);
        given(recommendationBatchRepository.findById(42L)).willReturn(Optional.of(otherBatch));

        assertUnavailable();

        RecommendationBatch deletedBatch = mock(RecommendationBatch.class);
        given(deletedBatch.getUserId()).willReturn(5L);
        given(deletedBatch.getDeletedAt()).willReturn(LocalDateTime.now());
        given(recommendationBatchRepository.findById(42L)).willReturn(Optional.of(deletedBatch));

        assertUnavailable();
        verifyNoInteractions(recommendationItemRepository);
    }

    @Test
    void 존재하지_않는_배치는_조회할_수_없다() {
        User requester = mock(User.class);
        given(requester.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        given(recommendationBatchRepository.findById(42L)).willReturn(Optional.empty());

        assertUnavailable();
    }

    @Test
    void 양수가_아닌_커서는_거부한다() {
        assertThatThrownBy(() -> service.getRecommendationItems(5L, 42L, 0L))
            .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.getRecommendationItems(5L, 42L, -1L))
            .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(userRepository, recommendationBatchRepository,
            recommendationItemRepository);
    }

    @Test
    void 다른_배치의_커서는_거부한다() {
        givenActiveRequesterAndBatch();
        given(recommendationItemRepository.findByIdAndRecommendationBatchId(101L, 42L))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRecommendationItems(5L, 42L, 101L))
            .isInstanceOf(RequestValidationException.class);
        verify(recommendationItemRepository, never()).findEligibleItems(
            42L, 5L, null, 101L, PageRequest.of(0, 21));
    }

    private void givenActiveRequesterAndBatch() {
        User requester = mock(User.class);
        given(requester.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(5L)).willReturn(Optional.of(requester));
        RecommendationBatch batch = mock(RecommendationBatch.class);
        given(batch.getUserId()).willReturn(5L);
        given(recommendationBatchRepository.findById(42L)).willReturn(Optional.of(batch));
    }

    private RecommendationItemCandidateRow row(Long itemId, Long memberId) {
        return new RecommendationItemCandidateRow(
            itemId, memberId, LocalDate.now().minusYears(29), "하리", "개발자",
            Mbti.INFP, "서울특별시", "강남구");
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.getRecommendationItems(5L, 42L, null))
            .isInstanceOf(RecommendationBusinessException.class)
            .satisfies(exception -> assertThat(
                ((RecommendationBusinessException) exception).getErrorCode())
                .isEqualTo(RecommendationErrorCode.RESOURCE_NOT_AVAILABLE));
    }
}
