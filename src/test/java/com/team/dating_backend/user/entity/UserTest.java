package com.team.dating_backend.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void 회원을_생성할_때_추천_랜덤_키를_생성한다() {
        User user = User.create(
            "회원", LocalDate.of(2000, 1, 1), Gender.FEMALE, LocalDateTime.now());

        assertThat(user.getRecommendationRandomKey())
            .isNotNull()
            .isNotNegative()
            .isLessThan(Long.MAX_VALUE);
    }

    @Test
    void 카카오톡_닉네임이_바뀐_경우에만_이름과_수정_시각을_갱신한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime changedAt = createdAt.plusDays(1);
        User user = User.create(
            "기존이름", LocalDate.of(2000, 1, 1), Gender.MALE, createdAt);

        user.updateName("Alice", changedAt);

        assertThat(user.getName()).isEqualTo("Alice");
        assertThat(user.getUpdatedAt()).isEqualTo(changedAt);

        user.updateName("Alice", changedAt.plusDays(1));

        assertThat(user.getUpdatedAt()).isEqualTo(changedAt);
    }
}
