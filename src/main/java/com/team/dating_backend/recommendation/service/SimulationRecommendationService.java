package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationCandidateRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulationRecommendationService {
    private final UserRepository users;
    private final RecommendationBatchCreateService batches;
    private final RecommendationBatchRepository batchRepository;
    private final RecommendationCandidateRepository candidates;
    private final RecommendationItemRepository items;

    @Transactional
    public void add(Long ownerId, Long candidateId) {
        var owner = users.findByIdForUpdate(ownerId).orElse(null);
        if (owner == null || owner.getStatus() != UserStatus.ACTIVE
            || !candidates.isEligibleWithoutPreferences(ownerId, candidateId)) {
            return;
        }
        batches.createRecommendationBatch(ownerId);
        var batch = batchRepository.findByUserIdAndDeletedAtIsNull(ownerId).orElseThrow();
        List<RecommendationItem> stored = items.findAllByRecommendationBatchId(batch.getId());
        RecommendationItem item = stored.stream()
            .filter(existing -> existing.getCandidateUserId().equals(candidateId))
            .findFirst().orElse(null);
        if (item == null) {
            int lastRank = stored.stream().mapToInt(RecommendationItem::getRankingOrder).max().orElse(0);
            item = new RecommendationItem(batch.getId(), candidateId, Math.incrementExact(lastRank));
            item.markSimulationRequested();
            items.save(item);
        } else {
            item.markSimulationRequested();
        }
    }
}
