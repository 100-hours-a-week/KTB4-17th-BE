package com.team.dating_backend.recommendation.entity;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recommendation_preferences")
public class RecommendationPreference {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "min_age")
    private Short minAge;

    @Column(name = "max_age")
    private Short maxAge;

    @Column(name = "min_height")
    private Short minHeight;

    @Column(name = "max_height")
    private Short maxHeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "religion", nullable = false, columnDefinition = "JSON")
    private List<Religion> religion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "drinking", nullable = false, columnDefinition = "JSON")
    private List<Drinking> drinking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "smoking", nullable = false, columnDefinition = "JSON")
    private List<Smoking> smoking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferred_genders", nullable = true, columnDefinition = "JSON")
    private List<Gender> preferredGenders;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RecommendationPreference(User user, LocalDateTime now) {
        this(user, null, null, null, null, List.of(), List.of(), List.of(),
            defaultTargetGenders(user.getGender()), now);
    }

    public RecommendationPreference(
        User user,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking,
        LocalDateTime now) {
        this(user, null, null, null, null, religion, drinking, smoking,
            defaultTargetGenders(user.getGender()), now);
    }

    public RecommendationPreference(
        User user,
        Short minAge,
        Short maxAge,
        Short minHeight,
        Short maxHeight,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking,
        LocalDateTime now) {
        this(user, minAge, maxAge, minHeight, maxHeight, religion, drinking, smoking,
            defaultTargetGenders(user.getGender()), now);
    }

    public RecommendationPreference(
        User user,
        Short minAge,
        Short maxAge,
        Short minHeight,
        Short maxHeight,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking,
        List<Gender> preferredGenders,
        LocalDateTime now) {
        this.user = user;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.religion = List.copyOf(religion);
        this.drinking = List.copyOf(drinking);
        this.smoking = List.copyOf(smoking);
        this.preferredGenders = normalizeTargetGenders(preferredGenders, user.getGender());
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean updatePreferences(
        Short minAge,
        Short maxAge,
        Short minHeight,
        Short maxHeight,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking,
        List<Gender> preferredGenders,
        LocalDateTime now) {
        List<Religion> nextReligion = List.copyOf(religion);
        List<Drinking> nextDrinking = List.copyOf(drinking);
        List<Smoking> nextSmoking = List.copyOf(smoking);
        List<Gender> nextPreferredGenders = preferredGenders == null
            ? getPreferredGenders()
            : normalizeTargetGenders(preferredGenders, user.getGender());
        if (Objects.equals(this.minAge, minAge)
            && Objects.equals(this.maxAge, maxAge)
            && Objects.equals(this.minHeight, minHeight)
            && Objects.equals(this.maxHeight, maxHeight)
            && containsSameValues(this.religion, nextReligion)
            && containsSameValues(this.drinking, nextDrinking)
            && containsSameValues(this.smoking, nextSmoking)
            && containsSameValues(getPreferredGenders(), nextPreferredGenders)) {
            return false;
        }

        this.minAge = minAge;
        this.maxAge = maxAge;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.religion = nextReligion;
        this.drinking = nextDrinking;
        this.smoking = nextSmoking;
        this.preferredGenders = nextPreferredGenders;
        this.updatedAt = now;
        return true;
    }

    public boolean updatePreferences(
        Short minAge,
        Short maxAge,
        Short minHeight,
        Short maxHeight,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking,
        LocalDateTime now) {
        return updatePreferences(minAge, maxAge, minHeight, maxHeight,
            religion, drinking, smoking, null, now);
    }

    public List<Religion> getReligion() {
        return List.copyOf(religion);
    }

    public List<Drinking> getDrinking() {
        return List.copyOf(drinking);
    }

    public List<Smoking> getSmoking() {
        return List.copyOf(smoking);
    }

    public List<Gender> getPreferredGenders() {
        return normalizeTargetGenders(preferredGenders, user.getGender());
    }

    public static List<Gender> defaultTargetGenders(Gender requesterGender) {
        return List.of(requesterGender == Gender.MALE ? Gender.FEMALE : Gender.MALE);
    }

    private static List<Gender> normalizeTargetGenders(List<Gender> genders, Gender requesterGender) {
        if (genders == null || genders.isEmpty()) {
            return defaultTargetGenders(requesterGender);
        }
        return List.copyOf(genders);
    }

    private <T> boolean containsSameValues(List<T> current, List<T> requested) {
        return Set.copyOf(current).equals(Set.copyOf(requested));
    }
}
