package com.team.dating_backend.recommendation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPreferenceTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Test
    void 최초_생성_시_생성_시각과_수정_시각을_같은_값으로_기록한다() {
        RecommendationPreference preference = new RecommendationPreference(user(), CREATED_AT);

        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 같은_선택값을_다른_순서로_요청하면_수정_시각을_유지한다() {
        RecommendationPreference preference = new RecommendationPreference(user(),
            List.of(Religion.CATHOLIC, Religion.NONE),
            List.of(Drinking.NEVER, Drinking.SOCIAL),
            List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL), CREATED_AT);

        preference.updatePreferences(null, null, null, null,
            List.of(Religion.NONE, Religion.CATHOLIC),
            List.of(Drinking.SOCIAL, Drinking.NEVER),
            List.of(Smoking.OCCASIONAL, Smoking.NON_SMOKER), CREATED_AT.plusMinutes(10));

        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getReligion()).containsExactly(Religion.CATHOLIC, Religion.NONE);
        assertThat(preference.getDrinking()).containsExactly(Drinking.NEVER, Drinking.SOCIAL);
        assertThat(preference.getSmoking())
            .containsExactly(Smoking.NON_SMOKER, Smoking.OCCASIONAL);
    }

    @Test
    void 조건을_초기화하면_수정_시각을_바꾸고_반복_초기화하면_유지한다() {
        RecommendationPreference preference = new RecommendationPreference(user(),
            (short) 25, (short) 32, (short) 160, (short) 180, List.of(Religion.NONE),
            List.of(Drinking.NEVER), List.of(Smoking.NON_SMOKER), CREATED_AT);
        LocalDateTime resetAt = CREATED_AT.plusMinutes(10);

        preference.updatePreferences(null, null, null, null,
            List.of(), List.of(), List.of(), resetAt);

        assertThat(preference.getMinAge()).isNull();
        assertThat(preference.getMaxAge()).isNull();
        assertThat(preference.getMinHeight()).isNull();
        assertThat(preference.getMaxHeight()).isNull();
        assertThat(preference.getReligion()).isEmpty();
        assertThat(preference.getDrinking()).isEmpty();
        assertThat(preference.getSmoking()).isEmpty();
        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isEqualTo(resetAt);

        preference.updatePreferences(null, null, null, null,
            List.of(), List.of(), List.of(), resetAt.plusMinutes(10));

        assertThat(preference.getUpdatedAt()).isEqualTo(resetAt);
    }

    @Test
    void 호출자가_전달한_목록이나_반환한_목록으로_조건을_직접_바꿀_수_없다() {
        List<Religion> religions = new ArrayList<>(List.of(Religion.NONE));
        RecommendationPreference preference = new RecommendationPreference(user(), religions, List.of(), List.of(),
            CREATED_AT);

        religions.add(Religion.CATHOLIC);

        assertThat(preference.getReligion()).containsExactly(Religion.NONE);
        assertThatThrownBy(() -> preference.getReligion().add(Religion.OTHER))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 같은_숫자_경계를_요청하면_수정_시각을_유지한다() {
        RecommendationPreference preference = new RecommendationPreference(user(),
            (short) 25, (short) 32, (short) 160, (short) 180,
            List.of(), List.of(), List.of(), CREATED_AT);

        preference.updatePreferences((short) 25, (short) 32, (short) 160, (short) 180,
            List.of(), List.of(), List.of(), CREATED_AT.plusMinutes(10));

        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 나이_경계만_변경해도_수정_시각을_변경한다() {
        RecommendationPreference preference = new RecommendationPreference(user(), CREATED_AT);
        LocalDateTime changedAt = CREATED_AT.plusMinutes(10);

        preference.updatePreferences((short) 25, null, null, null,
            List.of(), List.of(), List.of(), changedAt);

        assertThat(preference.getMinAge()).isEqualTo((short) 25);
        assertThat(preference.getMaxAge()).isNull();
        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isEqualTo(changedAt);
    }

    @Test
    void 키_경계만_변경해도_수정_시각을_변경한다() {
        RecommendationPreference preference = new RecommendationPreference(user(), CREATED_AT);
        LocalDateTime changedAt = CREATED_AT.plusMinutes(10);

        preference.updatePreferences(null, null, (short) 170, (short) 190,
            List.of(), List.of(), List.of(), changedAt);

        assertThat(preference.getMinHeight()).isEqualTo((short) 170);
        assertThat(preference.getMaxHeight()).isEqualTo((short) 190);
        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isEqualTo(changedAt);
    }

    private User user() {
        return User.create("테스트", LocalDate.of(1996, 1, 1), Gender.MALE, CREATED_AT);
    }
}
