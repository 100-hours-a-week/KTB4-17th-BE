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
import java.util.Collections;
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
        User requester = userRepository.findByIdForUpdate(requesterUserId)
            .orElseThrow(
                () -> new RecommendationBusinessException(
                    RecommendationErrorCode.REQUESTER_NOT_ACTIVE));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }

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
        LocalDate today = LocalDate.now();
        LocalDate birthDateAfter = maxAge == null ? null : today.minusYears(maxAge + 1L);
        LocalDate birthDateOnOrBefore = minAge == null ? null : today.minusYears(minAge);
        Gender candidateGender = requester.getGender() == Gender.MALE ? Gender.FEMALE : Gender.MALE;

        List<Long> candidateUserIds = new ArrayList<>(recommendationCandidateRepository
            .findEligibleCandidateIds(
                requesterUserId, candidateGender, birthDateAfter, birthDateOnOrBefore,
                minHeight, maxHeight,
                religion.isEmpty(), religion.isEmpty() ? List.of(Religion.values()) : religion,
                drinking.isEmpty(), drinking.isEmpty() ? List.of(Drinking.values()) : drinking,
                smoking.isEmpty(), smoking.isEmpty() ? List.of(Smoking.values()) : smoking));
        LocalDateTime now = LocalDateTime.now();
        if (candidateUserIds.isEmpty()) {
            recommendationBatchRepository.markActiveBatchesDeleted(requesterUserId, now);
            return Optional.empty();
        }
        Collections.shuffle(candidateUserIds);

        boolean hasPreferenceFilters = minAge != null || maxAge != null
            || minHeight != null || maxHeight != null
            || !religion.isEmpty() || !drinking.isEmpty() || !smoking.isEmpty();
        RecommendationGenerationType generationType = hasPreferenceFilters
            ? RecommendationGenerationType.PREFERENCE
            : RecommendationGenerationType.DEFAULT;
        RecommendationBatch batch = recommendationBatchRepository
            .save(new RecommendationBatch(requesterUserId, generationType, now));
        List<RecommendationItem> items = new ArrayList<>(candidateUserIds.size());
        for (int index = 0; index < candidateUserIds.size(); index++) {
            items
                .add(new RecommendationItem(batch.getId(), candidateUserIds.get(index), index + 1));
        }
        recommendationItemRepository.saveAll(items);
        recommendationBatchRepository.markPreviousBatchesDeleted(
            requesterUserId, batch.getId(), now);
        return Optional
            .of(new RecommendationBatchCreateResponse(batch.getId(), batch.getCreatedAt()));
    }
}
