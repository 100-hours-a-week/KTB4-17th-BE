package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.dto.response.RecommendationBatchCreateResponse;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
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
    private final RecommendationPreferenceRepository recommendationPreferenceRepository;

    @Transactional
    public Optional<RecommendationBatchCreateResponse> createRecommendationBatch(
        Long requesterUserId) {
        User requester = activeRequesterForUpdate(requesterUserId);
        Optional<RecommendationBatch> existing = recommendationBatchRepository
            .findByUserIdAndDeletedAtIsNull(requesterUserId);
        if (existing.isPresent()) {
            return response(existing.get());
        }
        return synchronize(requesterUserId, requester, Optional.empty());
    }

    @Transactional
    public Optional<RecommendationBatchCreateResponse> refreshRecommendationBatch(Long requesterUserId) {
        User requester = activeRequesterForUpdate(requesterUserId);
        return synchronize(requesterUserId, requester, recommendationBatchRepository
            .findByUserIdAndDeletedAtIsNull(requesterUserId));
    }

    private User activeRequesterForUpdate(Long requesterUserId) {
        User requester = userRepository.findByIdForUpdate(requesterUserId)
            .orElseThrow(() -> new RecommendationBusinessException(
                RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }
        return requester;
    }

    private Optional<RecommendationBatchCreateResponse> synchronize(
        Long requesterUserId, User requester, Optional<RecommendationBatch> existing) {
        Optional<RecommendationPreference> preference = recommendationPreferenceRepository
            .findById(requesterUserId);
        Short minAge = preference.map(RecommendationPreference::getMinAge).orElse(null);
        Short maxAge = preference.map(RecommendationPreference::getMaxAge).orElse(null);
        Short minHeight = preference.map(RecommendationPreference::getMinHeight).orElse(null);
        Short maxHeight = preference.map(RecommendationPreference::getMaxHeight).orElse(null);
        List<Religion> religion = preference.map(RecommendationPreference::getReligion)
            .orElseGet(List::of);
        List<Drinking> drinking = preference.map(RecommendationPreference::getDrinking)
            .orElseGet(List::of);
        List<Smoking> smoking = preference.map(RecommendationPreference::getSmoking)
            .orElseGet(List::of);
        List<Gender> preferredGenders = preference.map(RecommendationPreference::getPreferredGenders)
            .orElseGet(() -> RecommendationPreference.defaultTargetGenders(requester.getGender()));
        LocalDate today = LocalDate.now();
        LocalDate birthDateAfter = maxAge == null ? null : today.minusYears(maxAge + 1L);
        LocalDate birthDateOnOrBefore = minAge == null ? null : today.minusYears(minAge);

        List<Long> candidateUserIds = findEligibleCandidateIds(
            requesterUserId, preferredGenders, birthDateAfter, birthDateOnOrBefore,
            minHeight, maxHeight,
            religion.isEmpty(), religion.isEmpty() ? List.of(Religion.values()) : religion,
            drinking.isEmpty(), drinking.isEmpty() ? List.of(Drinking.values()) : drinking,
            smoking.isEmpty(), smoking.isEmpty() ? List.of(Smoking.values()) : smoking);
        boolean hasPreferenceFilters = minAge != null || maxAge != null
            || minHeight != null || maxHeight != null
            || !religion.isEmpty() || !drinking.isEmpty() || !smoking.isEmpty()
            || !preferredGenders.equals(RecommendationPreference.defaultTargetGenders(requester.getGender()));
        RecommendationGenerationType generationType = hasPreferenceFilters
            ? RecommendationGenerationType.PREFERENCE
            : RecommendationGenerationType.DEFAULT;
        RecommendationBatch batch = existing.orElseGet(() -> recommendationBatchRepository
            .save(new RecommendationBatch(requesterUserId, generationType, LocalDateTime.now())));
        batch.updateGenerationType(generationType);
        List<RecommendationItem> stored = existing.isPresent()
            ? recommendationItemRepository.findAllByRecommendationBatchId(batch.getId())
            : List.of();
        Set<Long> eligible = new LinkedHashSet<>(candidateUserIds);
        Set<Long> storedCandidates = new HashSet<>();
        List<Long> removedIds = new ArrayList<>();
        int lastRanking = 0;
        for (RecommendationItem item : stored) {
            storedCandidates.add(item.getCandidateUserId());
            lastRanking = Math.max(lastRanking, item.getRankingOrder());
            if (!eligible.contains(item.getCandidateUserId())) {
                removedIds.add(item.getId());
            }
        }
        // Bound IN-clause size while keeping each user's entire update atomic.
        for (int offset = 0; offset < removedIds.size(); offset += 500) {
            recommendationItemRepository.deleteItems(batch.getId(),
                removedIds.subList(offset, Math.min(offset + 500, removedIds.size())));
        }
        List<RecommendationItem> added = new ArrayList<>();
        for (Long candidateId : eligible) {
            if (!storedCandidates.contains(candidateId)) {
                lastRanking = Math.incrementExact(lastRanking);
                added.add(new RecommendationItem(batch.getId(), candidateId, lastRanking));
            }
        }
        if (!added.isEmpty()) {
            recommendationItemRepository.saveAll(added);
        }
        return response(batch);
    }

    private Optional<RecommendationBatchCreateResponse> response(RecommendationBatch batch) {
        return Optional.of(new RecommendationBatchCreateResponse(batch.getId(), batch.getCreatedAt()));
    }

    private List<Long> findEligibleCandidateIds(
        Long requesterUserId,
        List<Gender> preferredGenders,
        LocalDate birthDateAfter,
        LocalDate birthDateOnOrBefore,
        Short minHeight,
        Short maxHeight,
        boolean religionUnrestricted,
        List<Religion> religion,
        boolean drinkingUnrestricted,
        List<Drinking> drinking,
        boolean smokingUnrestricted,
        List<Smoking> smoking) {
        if (preferredGenders.size() == 1) {
            return recommendationCandidateRepository.findEligibleCandidateIds(
                requesterUserId, preferredGenders.get(0), birthDateAfter, birthDateOnOrBefore,
                minHeight, maxHeight, religionUnrestricted, religion,
                drinkingUnrestricted, drinking, smokingUnrestricted, smoking);
        }
        return recommendationCandidateRepository.findEligibleCandidateIdsByGenders(
            requesterUserId, preferredGenders, birthDateAfter, birthDateOnOrBefore,
            minHeight, maxHeight, religionUnrestricted, religion,
            drinkingUnrestricted, drinking, smokingUnrestricted, smoking);
    }
}
