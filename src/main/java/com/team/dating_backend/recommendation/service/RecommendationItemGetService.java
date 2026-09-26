package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.recommendation.dto.response.RecommendationCandidateResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemPageInfo;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemsGetResponse;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemCandidateRow;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationItemGetService {

    private static final int ITEMS_PER_REQUEST = 20;

    private final UserRepository userRepository;
    private final RecommendationBatchRepository recommendationBatchRepository;
    private final RecommendationItemRepository recommendationItemRepository;

    @Transactional(readOnly = true)
    public RecommendationItemsGetResponse getRecommendationItems(
        Long requesterUserId, Long batchId, Long cursor) {
        validateCursor(cursor);
        User requester = userRepository.findById(requesterUserId)
            .orElseThrow(() -> new RecommendationBusinessException(
                RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }
        recommendationBatchRepository.findById(batchId)
            .filter(batch -> batch.getUserId().equals(requesterUserId)
                && batch.getDeletedAt() == null)
            .orElseThrow(() -> new RecommendationBusinessException(
                RecommendationErrorCode.RESOURCE_NOT_AVAILABLE));

        Integer cursorRankingOrder = null;
        if (cursor != null) {
            RecommendationItem cursorItem = recommendationItemRepository
                .findByIdAndRecommendationBatchId(cursor, batchId)
                .orElseThrow(() -> new RequestValidationException(
                    List.of(new FieldErrorResponse("cursor", "must reference an item in the batch"))));
            cursorRankingOrder = cursorItem.getRankingOrder();
        }

        List<RecommendationItemCandidateRow> rows = recommendationItemRepository.findEligibleItems(
            batchId, requesterUserId, cursorRankingOrder, cursor,
            PageRequest.of(0, ITEMS_PER_REQUEST + 1));
        boolean hasNext = rows.size() > ITEMS_PER_REQUEST;
        LocalDate today = LocalDate.now();
        List<RecommendationItemResponse> items = rows.stream()
            .limit(ITEMS_PER_REQUEST)
            .map(row -> toItemResponse(row, today))
            .toList();
        Long nextCursor = hasNext ? items.getLast().itemId() : null;
        return new RecommendationItemsGetResponse(
            items, new RecommendationItemPageInfo(nextCursor, hasNext, batchId));
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor <= 0) {
            throw new RequestValidationException(
                List.of(new FieldErrorResponse("cursor", "must be a positive item ID")));
        }
    }

    private RecommendationItemResponse toItemResponse(
        RecommendationItemCandidateRow row, LocalDate today) {
        String region = Stream.of(row.provinceName(), row.regionName())
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.joining(" "));
        if (region.isEmpty()) {
            region = null;
        }
        Integer age = row.birthDate() == null
            ? null
            : Period.between(row.birthDate(), today)
                .getYears();
        RecommendationCandidateResponse candidate = new RecommendationCandidateResponse(
            row.memberId(), row.nickname(), age, row.job(), region, row.mbti());
        return new RecommendationItemResponse(row.itemId(), candidate);
    }
}
