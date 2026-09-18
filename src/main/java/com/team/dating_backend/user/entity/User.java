package com.team.dating_backend.user.entity;

import com.team.dating_backend.user.enums.FaceVerificationStatus;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private UserStatus status;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "birth_date", nullable = false)
  private LocalDate birthDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "gender", nullable = false)
  private Gender gender;

  @Enumerated(EnumType.STRING)
  @Column(name = "face_verification_status", nullable = false)
  private FaceVerificationStatus faceVerificationStatus;

  @Column(name = "last_accessed_at", nullable = false)
  private LocalDateTime lastAccessedAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  public static User create(String name, LocalDate birthDate, Gender gender, LocalDateTime now) {
    User user = new User();
    user.status = UserStatus.ONBOARDING;
    user.name = name;
    user.birthDate = birthDate;
    user.gender = gender;
    user.faceVerificationStatus = FaceVerificationStatus.NOT_VERIFIED;
    user.lastAccessedAt = now;
    user.createdAt = now;
    user.updatedAt = now;
    return user;
  }
}
