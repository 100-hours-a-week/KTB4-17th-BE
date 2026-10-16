package com.team.dating_backend.recommendation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.node.ObjectNode;

@JsonTest
class RecommendationPreferenceSaveRequestJsonTest {

    private static final String VALID_REQUEST = """
        {
          "minAge": 25,
          "maxAge": 30,
          "minHeight": 160,
          "maxHeight": 180,
          "religion": ["NONE", "CATHOLIC"],
          "drinking": ["NEVER", "SOCIAL"],
          "smoking": ["NON_SMOKER", "OCCASIONAL"]
        }
        """;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 전체_필드가_있는_요청을_타입이_있는_DTO로_변환한다() {
        RecommendationPreferenceSaveRequest request = objectMapper.readValue(
            VALID_REQUEST, RecommendationPreferenceSaveRequest.class);

        assertThat(request).isEqualTo(new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180,
            List.of(Religion.NONE, Religion.CATHOLIC),
            List.of(Drinking.NEVER, Drinking.SOCIAL),
            List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL)));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "minAge", "maxAge", "minHeight", "maxHeight", "religion", "drinking", "smoking"
        }
    )
    void 어느_필드든_누락되면_DTO를_생성하지_않는다(String field) {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.remove(field);

        assertThatThrownBy(() -> objectMapper.readValue(body.toString(),
            RecommendationPreferenceSaveRequest.class))
            .isInstanceOf(MismatchedInputException.class)
            .hasMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "minAge", "maxAge", "minHeight", "maxHeight", "religion", "drinking", "smoking"
        }
    )
    void 어느_필드든_명시적인_null은_허용한다(String field) {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.putNull(field);

        assertThatCode(() -> objectMapper.readValue(body.toString(), RecommendationPreferenceSaveRequest.class))
            .doesNotThrowAnyException();
    }

    @Test
    void 모든_필드를_null로_명시한_초기화_요청을_허용한다() {
        String body = """
            {
              "minAge": null,
              "maxAge": null,
              "minHeight": null,
              "maxHeight": null,
              "religion": null,
              "drinking": null,
              "smoking": null
            }
            """;

        RecommendationPreferenceSaveRequest request = objectMapper.readValue(
            body, RecommendationPreferenceSaveRequest.class);

        assertThat(request).isEqualTo(new RecommendationPreferenceSaveRequest(
            null, null, null, null, null, null, null));
    }

    @Test
    void 빈_JSON_객체는_초기화_요청으로_해석하지_않는다() {
        assertThatThrownBy(() -> objectMapper.readValue("{}", RecommendationPreferenceSaveRequest.class))
            .isInstanceOf(MismatchedInputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"religion", "drinking", "smoking"})
    void 배열_내부_null은_Service에서_구체적인_오류로_검증할_수_있도록_유지한다(String field) {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.set(field, objectMapper.readTree("[null]"));

        RecommendationPreferenceSaveRequest request = objectMapper.readValue(
            body.toString(), RecommendationPreferenceSaveRequest.class);

        List<?> selectedValues = switch (field) {
            case "religion" -> request.religion();
            case "drinking" -> request.drinking();
            case "smoking" -> request.smoking();
            default -> throw new IllegalArgumentException(field);
        };
        assertThat(selectedValues).hasSize(1);
        assertThat(selectedValues.getFirst()).isNull();
    }

    @Test
    void 필수_속성_설정은_기존_프로필_DTO의_필드_누락_허용에_영향을_주지_않는다() {
        ProfileSaveRequest request = objectMapper.readValue("{}", ProfileSaveRequest.class);

        assertThat(request).isNotNull();
        assertThat(request.nickname()).isNull();
        assertThat(request.height()).isNull();
    }
}
