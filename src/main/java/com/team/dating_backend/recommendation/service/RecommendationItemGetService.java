package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.recommendation.dto.response.RecommendationCandidateResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemPageInfo;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemsGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationProfileImageResponse;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemCandidateRow;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
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
    private final ProfileImageGetService profileImageGetService;

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
                .orElseThrow(RequestValidationException::new);
            cursorRankingOrder = cursorItem.getRankingOrder();
        }

        List<RecommendationItemCandidateRow> rows = recommendationItemRepository.findEligibleItems(
            batchId, requesterUserId, cursorRankingOrder, cursor,
            PageRequest.of(0, ITEMS_PER_REQUEST + 1));
        boolean hasNext = rows.size() > ITEMS_PER_REQUEST;
        List<RecommendationItemCandidateRow> responseRows = rows.stream()
            .limit(ITEMS_PER_REQUEST)
            .toList();
        Map<Long, List<ProfileImageAccessResult>> profileImagesByMemberId = profileImageGetService
            .getProfileImagesByMemberIds(responseRows.stream()
                .map(RecommendationItemCandidateRow::memberId)
                .distinct()
                .toList());
        LocalDate today = LocalDate.now();
        List<RecommendationItemResponse> items = responseRows.stream()
            .map(row -> toItemResponse(
                row,
                today,
                profileImagesByMemberId.getOrDefault(row.memberId(), List.of())))
            .toList();
        Long nextCursor = hasNext ? items.getLast().itemId() : null;
        return new RecommendationItemsGetResponse(
            items, new RecommendationItemPageInfo(nextCursor, hasNext, batchId));
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor <= 0) {
            throw new RequestValidationException();
        }
    }

    private RecommendationItemResponse toItemResponse(
        RecommendationItemCandidateRow row,
        LocalDate today,
        List<ProfileImageAccessResult> profileImages) {
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
        List<RecommendationProfileImageResponse> images = profileImages.stream()
            .map(image -> new RecommendationProfileImageResponse(
                image.fileId(), image.displayOrder(), image.imageUrl(), image.expiresAt()))
            .toList();
        RecommendationCandidateResponse candidate = new RecommendationCandidateResponse(
            row.memberId(), row.nickname(), age, row.job(), region, row.mbti(), images);
        return new RecommendationItemResponse(row.itemId(), candidate);
    }
}
