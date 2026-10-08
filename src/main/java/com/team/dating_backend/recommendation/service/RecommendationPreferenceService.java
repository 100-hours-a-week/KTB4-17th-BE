package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.dto.request.RecommendationPreferenceSaveRequest;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceResponse;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import com.team.dating_backend.recommendation.enums.RecommendationPreferenceErrorCode;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.recommendation.exception.RecommendationPreferenceBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationPreferenceRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationPreferenceService {

    private static final int MIN_AGE = 19;
    private static final int MAX_AGE = 39;
    private static final int MIN_HEIGHT = 130;
    private static final int MAX_HEIGHT = 220;

    private final UserRepository userRepository;
    private final RecommendationPreferenceRepository recommendationPreferenceRepository;

    @Transactional(readOnly = true)
    public RecommendationPreferenceGetResponse getPreferences(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE));
        validateActiveUser(user);

        RecommendationPreferenceResponse preference = recommendationPreferenceRepository.findById(userId)
            .map(RecommendationPreferenceResponse::from)
            .orElseGet(RecommendationPreferenceResponse::unrestricted);
        return new RecommendationPreferenceGetResponse(preference);
    }

    @Transactional
    public void savePreferences(Long userId, RecommendationPreferenceSaveRequest request) {
        validateRequest(request);

        Short minAge = asShort(request.minAge());
        Short maxAge = asShort(request.maxAge());
        Short minHeight = asShort(request.minHeight());
        Short maxHeight = asShort(request.maxHeight());
        List<Religion> religion = emptyIfNull(request.religion());
        List<Drinking> drinking = emptyIfNull(request.drinking());
        List<Smoking> smoking = emptyIfNull(request.smoking());

        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE));
        validateActiveUser(user);

        LocalDateTime now = LocalDateTime.now();

        RecommendationPreference preference = recommendationPreferenceRepository.findById(userId).orElse(null);
        if (preference == null) {
            recommendationPreferenceRepository.save(new RecommendationPreference(
                user, minAge, maxAge, minHeight, maxHeight, religion, drinking, smoking, now));
        } else {
            preference.updatePreferences(
                minAge, maxAge, minHeight, maxHeight, religion, drinking, smoking, now);
        }
    }

    private void validateActiveUser(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationPreferenceBusinessException(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE);
        }
    }

    private void validateRequest(RecommendationPreferenceSaveRequest request) {
        if (isOutOfRange(request.minAge(), MIN_AGE, MAX_AGE)
            || isOutOfRange(request.maxAge(), MIN_AGE, MAX_AGE)) {
            throw new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.PREFERENCE_AGE_OUT_OF_RANGE);
        }
        if (isOutOfRange(request.minHeight(), MIN_HEIGHT, MAX_HEIGHT)
            || isOutOfRange(request.maxHeight(), MIN_HEIGHT, MAX_HEIGHT)) {
            throw new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.PREFERENCE_HEIGHT_OUT_OF_RANGE);
        }

        if (request.minAge() != null && request.maxAge() != null
            && request.minAge() > request.maxAge()) {
            throw new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.PREFERENCE_AGE_RANGE_INVALID);
        }
        if (request.minHeight() != null && request.maxHeight() != null
            && request.minHeight() > request.maxHeight()) {
            throw new RecommendationPreferenceBusinessException(
                RecommendationPreferenceErrorCode.PREFERENCE_HEIGHT_RANGE_INVALID);
        }

        validateSelectedValues(request.religion(),
            RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_NULL_ELEMENT,
            RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_DUPLICATE_VALUE);
        validateSelectedValues(request.drinking(),
            RecommendationPreferenceErrorCode.PREFERENCE_DRINKING_NULL_ELEMENT,
            RecommendationPreferenceErrorCode.PREFERENCE_DRINKING_DUPLICATE_VALUE);
        validateSelectedValues(request.smoking(),
            RecommendationPreferenceErrorCode.PREFERENCE_SMOKING_NULL_ELEMENT,
            RecommendationPreferenceErrorCode.PREFERENCE_SMOKING_DUPLICATE_VALUE);
    }

    private <T> void validateSelectedValues(
        List<T> values,
        RecommendationPreferenceErrorCode nullElementErrorCode,
        RecommendationPreferenceErrorCode duplicateValueErrorCode) {
        if (values == null) {
            return;
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new RecommendationPreferenceBusinessException(nullElementErrorCode);
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new RecommendationPreferenceBusinessException(duplicateValueErrorCode);
        }
    }

    private boolean isOutOfRange(Integer value, int min, int max) {
        return value != null && (value < min || value > max);
    }

    private Short asShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
