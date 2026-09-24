package com.team.dating_backend.profile.entity;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(name = "profiles", uniqueConstraints = @UniqueConstraint(name = "uk_profiles_user_id", columnNames = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nickname", length = 10)
    private String nickname;

    @Column(name = "height")
    private Short height;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_type", length = 30)
    private BodyType bodyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level", length = 30)
    private EducationLevel educationLevel;

    @Column(name = "job", length = 50)
    private String job;

    @Enumerated(EnumType.STRING)
    @Column(name = "religion", length = 30)
    private Religion religion;

    @Enumerated(EnumType.STRING)
    @Column(name = "mbti", length = 4)
    private Mbti mbti;

    @Enumerated(EnumType.STRING)
    @Column(name = "drinking", length = 30)
    private Drinking drinking;

    @Enumerated(EnumType.STRING)
    @Column(name = "smoking", length = 30)
    private Smoking smoking;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_region_id")
    private ActivityRegion activityRegion;

    public Profile(User user) {
        this.user = user;
    }

    public void updateProfile(
        ActivityRegion activityRegion,
        String nickname,
        Short height,
        BodyType bodyType,
        EducationLevel educationLevel,
        String job,
        Religion religion,
        Mbti mbti,
        Drinking drinking,
        Smoking smoking) {
        this.activityRegion = activityRegion;
        this.nickname = nickname;
        this.height = height;
        this.bodyType = bodyType;
        this.educationLevel = educationLevel;
        this.job = job;
        this.religion = religion;
        this.mbti = mbti;
        this.drinking = drinking;
        this.smoking = smoking;
    }
}
