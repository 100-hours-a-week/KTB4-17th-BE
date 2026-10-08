package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationCandidateRepository extends JpaRepository<User, Long> {

    @Query("""
        SELECT candidate.id FROM User candidate
        JOIN Profile profile ON profile.user = candidate AND profile.deletedAt IS NULL
        WHERE candidate.gender = :candidateGender
          AND (:birthDateAfter IS NULL OR candidate.birthDate > :birthDateAfter)
          AND (:birthDateOnOrBefore IS NULL OR candidate.birthDate <= :birthDateOnOrBefore)
          AND (:minHeight IS NULL OR profile.height >= :minHeight)
          AND (:maxHeight IS NULL OR profile.height <= :maxHeight)
          AND (:religionUnrestricted = true OR profile.religion IN :religion)
          AND (:drinkingUnrestricted = true OR profile.drinking IN :drinking)
          AND (:smokingUnrestricted = true OR profile.smoking IN :smoking)
          AND
        """ + RecommendationEligibilityQuery.ELIGIBLE_CANDIDATE_PREDICATE + """
        ORDER BY candidate.id ASC
        """)
    List<Long> findEligibleCandidateIds(
        @Param("requesterUserId") Long requesterUserId,
        @Param("candidateGender") Gender candidateGender,
        @Param("birthDateAfter") LocalDate birthDateAfter,
        @Param("birthDateOnOrBefore") LocalDate birthDateOnOrBefore,
        @Param("minHeight") Short minHeight,
        @Param("maxHeight") Short maxHeight,
        @Param("religionUnrestricted") boolean religionUnrestricted,
        @Param("religion") List<Religion> religion,
        @Param("drinkingUnrestricted") boolean drinkingUnrestricted,
        @Param("drinking") List<Drinking> drinking,
        @Param("smokingUnrestricted") boolean smokingUnrestricted,
        @Param("smoking") List<Smoking> smoking);
}
