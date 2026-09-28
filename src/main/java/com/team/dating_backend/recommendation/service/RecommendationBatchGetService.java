package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.recommendation.dto.response.RecommendationBatchGetResponse;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationBatchGetService {

    private final UserRepository userRepository;
    private final RecommendationBatchRepository recommendationBatchRepository;

    @Transactional(readOnly = true)
    public RecommendationBatchGetResponse getActiveRecommendationBatch(Long requesterUserId) {
        User requester = userRepository.findById(requesterUserId)
            .orElseThrow(
                () -> new RecommendationBusinessException(
                    RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }

        Long batchId = recommendationBatchRepository
            .findByUserIdAndDeletedAtIsNull(requesterUserId)
            .map(RecommendationBatch::getId)
            .orElse(null);
        return new RecommendationBatchGetResponse(batchId);
    }
}
