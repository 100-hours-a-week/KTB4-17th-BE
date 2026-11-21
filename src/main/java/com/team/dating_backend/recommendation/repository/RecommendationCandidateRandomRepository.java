package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.util.List;

public interface RecommendationCandidateRandomRepository {

    List<RecommendationCandidatePosition> findEligibleCandidatePositionsByGenders(
        Long requesterUserId,
        List<Gender> candidateGenders,
        LocalDate birthDateAfter,
        LocalDate birthDateOnOrBefore,
        Short minHeight,
        Short maxHeight,
        boolean religionUnrestricted,
        List<Religion> religion,
        boolean drinkingUnrestricted,
        List<Drinking> drinking,
        boolean smokingUnrestricted,
        List<Smoking> smoking,
        Long afterRandomKey,
        Long afterUserId,
        int limit);
}
