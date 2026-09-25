package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.recommendation.dto.response.RecommendationBatchCreateResponse;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationCandidateRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationBatchCreateService {

    private final UserRepository userRepository;
    private final RecommendationCandidateRepository recommendationCandidateRepository;
    private final RecommendationBatchRepository recommendationBatchRepository;
    private final RecommendationItemRepository recommendationItemRepository;

    @Transactional
    public Optional<RecommendationBatchCreateResponse> createRecommendationBatch(Long requesterUserId) {
        User requester = userRepository.findById(requesterUserId)
            .orElseThrow(
                () -> new RecommendationBusinessException(
                    RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }

        List<Long> candidateUserIds = recommendationCandidateRepository
            .findEligibleCandidateIds(requesterUserId);
        LocalDateTime now = LocalDateTime.now();
        if (candidateUserIds.isEmpty()) {
            recommendationBatchRepository.markActiveBatchesDeleted(requesterUserId, now);
            return Optional.empty();
        }

        RecommendationBatch batch = recommendationBatchRepository
            .save(new RecommendationBatch(requesterUserId, now));
        List<RecommendationItem> items = new ArrayList<>(candidateUserIds.size());
        for (int index = 0; index < candidateUserIds.size(); index++) {
            items.add(new RecommendationItem(batch.getId(), candidateUserIds.get(index), index + 1));
        }
        recommendationItemRepository.saveAll(items);
        recommendationBatchRepository.markPreviousBatchesDeleted(
            requesterUserId, batch.getId(), now);
        return Optional.of(new RecommendationBatchCreateResponse(batch.getId(), batch.getCreatedAt()));
    }
}
