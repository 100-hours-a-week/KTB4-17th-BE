package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationHistoryCleanupService {

    private final RecommendationItemRepository itemRepository;
    private final RecommendationBatchRepository batchRepository;

    @Transactional
    public int purgeChunk(int size) {
        List<Long> batchIds = batchRepository.findDeletedBatchIds(PageRequest.of(0, 1));
        if (batchIds.isEmpty()) {
            return 0;
        }
        Long batchId = batchIds.getFirst();
        List<Long> itemIds = itemRepository.findItemIds(batchId, PageRequest.of(0, size));
        if (!itemIds.isEmpty()) {
            itemRepository.deleteItems(batchId, itemIds);
        }
        if (!itemRepository.existsByRecommendationBatchId(batchId)) {
            batchRepository.deleteAllByIdInBatch(batchIds);
            return itemIds.size() + 1;
        }
        return itemIds.size();
    }
}
