package com.team.dating_backend.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.dto.response.SuccessResponse;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

class RecommendationPreferenceServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    private UserRepository userRepository;
    private RecommendationPreferenceRepository recommendationPreferenceRepository;
    private RecommendationPreferenceService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recommendationPreferenceRepository = mock(RecommendationPreferenceRepository.class);
        service = new RecommendationPreferenceService(userRepository, recommendationPreferenceRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidSelectedValues")
    void 배열의_중복과_내부_null은_항목별_코드로_거절하고_DB를_호출하지_않는다(
        RecommendationPreferenceSaveRequest request, RecommendationPreferenceErrorCode errorCode) {
        assertError(request, errorCode);
    }

    private static Stream<Arguments> invalidSelectedValues() {
        return Stream.of(
            Arguments.of(selectedValues(Arrays.asList(Religion.NONE, null), List.of(), List.of()),
                RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_NULL_ELEMENT),
            Arguments.of(selectedValues(List.of(Religion.NONE, Religion.NONE), List.of(), List.of()),
                RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_DUPLICATE_VALUE),
            Arguments.of(selectedValues(List.of(), Arrays.asList(Drinking.NEVER, null), List.of()),
                RecommendationPreferenceErrorCode.PREFERENCE_DRINKING_NULL_ELEMENT),
            Arguments.of(selectedValues(List.of(), List.of(Drinking.NEVER, Drinking.NEVER), List.of()),
                RecommendationPreferenceErrorCode.PREFERENCE_DRINKING_DUPLICATE_VALUE),
            Arguments.of(selectedValues(List.of(), List.of(), Arrays.asList(Smoking.NON_SMOKER, null)),
                RecommendationPreferenceErrorCode.PREFERENCE_SMOKING_NULL_ELEMENT),
            Arguments.of(selectedValues(List.of(), List.of(),
                List.of(Smoking.NON_SMOKER, Smoking.NON_SMOKER)),
                RecommendationPreferenceErrorCode.PREFERENCE_SMOKING_DUPLICATE_VALUE));
    }

    @ParameterizedTest
    @MethodSource("validSelectedValues")
    void 여러_값과_일부_null_배열을_허용하고_null_배열만_빈_배열로_저장한다(
        RecommendationPreferenceSaveRequest request) {
        givenActiveUser();

        service.savePreferences(USER_ID, request);

        ArgumentCaptor<RecommendationPreference> captor = ArgumentCaptor.forClass(RecommendationPreference.class);
        verify(recommendationPreferenceRepository).save(captor.capture());
        RecommendationPreference preference = captor.getValue();
        assertThat(preference.getReligion())
            .containsExactlyElementsOf(request.religion() == null ? List.of() : request.religion());
        assertThat(preference.getDrinking())
            .containsExactlyElementsOf(request.drinking() == null ? List.of() : request.drinking());
        assertThat(preference.getSmoking())
            .containsExactlyElementsOf(request.smoking() == null ? List.of() : request.smoking());
    }

    private static Stream<RecommendationPreferenceSaveRequest> validSelectedValues() {
        return Stream.of(
            selectedValues(null, List.of(Drinking.NEVER), List.of(Smoking.NON_SMOKER)),
            selectedValues(List.of(Religion.NONE), null, List.of(Smoking.NON_SMOKER)),
            selectedValues(List.of(Religion.NONE), List.of(Drinking.NEVER), null),
            selectedValues(List.of(Religion.NONE, Religion.CATHOLIC),
                List.of(Drinking.NEVER, Drinking.SOCIAL),
                List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL)),
            selectedValues(List.of(Religion.values()), List.of(Drinking.values()),
                List.of(Smoking.values())));
    }

    @Test
    void 같은_배열에_null과_중복이_있으면_null_오류를_먼저_반환한다() {
        assertError(selectedValues(Arrays.asList(Religion.NONE, Religion.NONE, null),
            List.of(), List.of()), RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_NULL_ELEMENT);
    }

    @Test
    void 여러_배열이_잘못되면_종교_음주_흡연_순서로_첫_오류를_반환한다() {
        assertError(selectedValues(List.of(Religion.NONE, Religion.NONE),
            Arrays.asList(Drinking.NEVER, null),
            List.of(Smoking.NON_SMOKER, Smoking.NON_SMOKER)),
            RecommendationPreferenceErrorCode.PREFERENCE_RELIGION_DUPLICATE_VALUE);
    }

    @Test
    void 사용자_잠금을_기다리는_동안_원본_배열이_변경되어도_검증한_선택값을_저장한다() {
        User user = givenActiveUser();
        List<Religion> religions = new ArrayList<>(List.of(Religion.NONE));
        RecommendationPreferenceSaveRequest request = selectedValues(religions, List.of(), List.of());
        given(userRepository.findByIdForUpdate(USER_ID)).willAnswer(invocation -> {
            religions.add(Religion.NONE);
            return Optional.of(user);
        });

        service.savePreferences(USER_ID, request);

        ArgumentCaptor<RecommendationPreference> captor = ArgumentCaptor.forClass(RecommendationPreference.class);
        verify(recommendationPreferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getReligion()).containsExactly(Religion.NONE);
    }

    private static RecommendationPreferenceSaveRequest selectedValues(
        List<Religion> religion, List<Drinking> drinking, List<Smoking> smoking) {
        return new RecommendationPreferenceSaveRequest(null, null, null, null, religion, drinking, smoking);
    }

    @Test
    void 조회_시_행이_없으면_기본_조건을_반환하고_행을_생성하지_않는다() {
        givenActiveUserForRead();

        RecommendationPreferenceGetResponse response = service.getPreferences(USER_ID);

        assertThat(response.preference()).isEqualTo(RecommendationPreferenceResponse.unrestricted());
        verify(userRepository).findById(USER_ID);
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(recommendationPreferenceRepository, never()).save(any());
    }

    @Test
    void 기본_조건_응답은_숫자_null과_빈_배열을_포함한_일곱_필드를_반환한다() {
        givenActiveUserForRead();

        RecommendationPreferenceGetResponse response = service.getPreferences(USER_ID);

        assertThat(new ObjectMapper().writeValueAsString(
            SuccessResponse.of("preference_get_success", response)))
            .isEqualTo("{\"message\":\"preference_get_success\",\"data\":{\"preference\":{"
                + "\"minAge\":null,\"maxAge\":null,\"minHeight\":null,\"maxHeight\":null,"
                + "\"religion\":[],\"drinking\":[],\"smoking\":[]}}}");
    }

    @Test
    void 저장된_조건을_DTO로_반환하고_수정_시각을_유지한다() {
        User user = givenActiveUserForRead();
        RecommendationPreference preference = new RecommendationPreference(user,
            (short) 25, (short) 30, (short) 160, (short) 180,
            List.of(Religion.NONE, Religion.CATHOLIC), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), CREATED_AT);
        given(recommendationPreferenceRepository.findById(USER_ID)).willReturn(Optional.of(preference));

        RecommendationPreferenceGetResponse response = service.getPreferences(USER_ID);

        assertThat(response.preference()).isEqualTo(new RecommendationPreferenceResponse(
            (short) 25, (short) 30, (short) 160, (short) 180,
            List.of(Religion.NONE, Religion.CATHOLIC), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER)));
        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThatThrownBy(() -> response.preference().religion().add(Religion.OTHER))
            .isInstanceOf(UnsupportedOperationException.class);
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(recommendationPreferenceRepository, never()).save(any());
    }

    @Test
    void 조회_사용자가_없으면_선호_조건을_조회하지_않는다() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        RecommendationPreferenceBusinessException exception = catchThrowableOfType(
            RecommendationPreferenceBusinessException.class, () -> service.getPreferences(USER_ID));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode())
            .isEqualTo(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(recommendationPreferenceRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void 조회_사용자가_ACTIVE가_아니면_선호_조건을_조회하지_않는다(UserStatus status) {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(status);

        RecommendationPreferenceBusinessException exception = catchThrowableOfType(
            RecommendationPreferenceBusinessException.class, () -> service.getPreferences(USER_ID));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode())
            .isEqualTo(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(recommendationPreferenceRepository);
    }

    @Test
    void DB_조회_실패를_미설정_상태로_바꾸지_않는다() {
        givenActiveUserForRead();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
            "database unavailable");
        given(recommendationPreferenceRepository.findById(USER_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.getPreferences(USER_ID)).isSameAs(failure);
    }

    @Test
    void 허용_범위의_양끝_값을_받을_수_있다() {
        givenActiveUser();
        RecommendationPreferenceSaveRequest request = request(19, 39, 130, 220);

        assertThatCode(() -> service.savePreferences(USER_ID, request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
        {
            "18, 30, 160, 180, PREFERENCE_AGE_OUT_OF_RANGE",
            "40, 30, 160, 180, PREFERENCE_AGE_OUT_OF_RANGE",
            "25, 18, 160, 180, PREFERENCE_AGE_OUT_OF_RANGE",
            "25, 40, 160, 180, PREFERENCE_AGE_OUT_OF_RANGE",
            "25, 30, 129, 180, PREFERENCE_HEIGHT_OUT_OF_RANGE",
            "25, 30, 221, 180, PREFERENCE_HEIGHT_OUT_OF_RANGE",
            "25, 30, 160, 129, PREFERENCE_HEIGHT_OUT_OF_RANGE",
            "25, 30, 160, 221, PREFERENCE_HEIGHT_OUT_OF_RANGE"
        }
    )
    void 허용_범위를_벗어나면_나이와_키를_구분한_에러_코드가_발생한다(
        Integer minAge, Integer maxAge, Integer minHeight, Integer maxHeight,
        RecommendationPreferenceErrorCode errorCode) {
        assertError(request(minAge, maxAge, minHeight, maxHeight), errorCode);
    }

    @ParameterizedTest
    @CsvSource(
        value = {
            "null, null, null, null",
            "25, null, 170, null",
            "null, 30, null, 180",
            "25, null, null, 180",
            "null, 30, 170, null"
        }, nullValues = "null"
    )
    void null인_경계는_제한하지_않고_최소_최대_비교에서도_제외한다(
        Integer minAge, Integer maxAge, Integer minHeight, Integer maxHeight) {
        givenActiveUser();
        RecommendationPreferenceSaveRequest request = request(minAge, maxAge, minHeight, maxHeight);

        assertThatCode(() -> service.savePreferences(USER_ID, request)).doesNotThrowAnyException();
    }

    @Test
    void 최소와_최대가_같은_경계를_허용한다() {
        givenActiveUser();
        RecommendationPreferenceSaveRequest request = request(25, 25, 170, 170);

        assertThatCode(() -> service.savePreferences(USER_ID, request)).doesNotThrowAnyException();
    }

    @Test
    void 최소_나이가_최대_나이보다_크면_나이_관계_에러_코드가_발생한다() {
        assertError(request(30, 25, 160, 180),
            RecommendationPreferenceErrorCode.PREFERENCE_AGE_RANGE_INVALID);
    }

    @Test
    void 최소_키가_최대_키보다_크면_키_관계_에러_코드가_발생한다() {
        assertError(request(25, 30, 180, 160),
            RecommendationPreferenceErrorCode.PREFERENCE_HEIGHT_RANGE_INVALID);
    }

    @Test
    void 나이와_키_관계가_모두_잘못되면_나이_관계_오류를_먼저_반환한다() {
        assertError(request(30, 25, 180, 160),
            RecommendationPreferenceErrorCode.PREFERENCE_AGE_RANGE_INVALID);
    }

    @Test
    void 나이와_키가_모두_범위를_벗어나면_나이_범위_오류를_먼저_반환한다() {
        assertError(request(18, 40, 129, 221),
            RecommendationPreferenceErrorCode.PREFERENCE_AGE_OUT_OF_RANGE);
    }

    @Test
    void 나이_관계_오류와_키_범위_오류가_있으면_값의_범위_오류를_먼저_반환한다() {
        assertError(request(30, 25, 129, 180),
            RecommendationPreferenceErrorCode.PREFERENCE_HEIGHT_OUT_OF_RANGE);
    }

    private void assertError(RecommendationPreferenceSaveRequest request, RecommendationPreferenceErrorCode errorCode) {
        RecommendationPreferenceBusinessException exception = catchThrowableOfType(
            RecommendationPreferenceBusinessException.class, () -> service.savePreferences(USER_ID, request));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(errorCode);
        verifyNoInteractions(userRepository, recommendationPreferenceRepository);
    }

    @Test
    void 사용자가_없으면_저장을_거절한다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        RecommendationPreferenceBusinessException exception = catchThrowableOfType(
            RecommendationPreferenceBusinessException.class,
            () -> service.savePreferences(USER_ID, request(null, null, null, null)));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode())
            .isEqualTo(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(recommendationPreferenceRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아니면_선호_조건을_조회하거나_저장하지_않는다(UserStatus status) {
        User user = mock(User.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(status);

        RecommendationPreferenceBusinessException exception = catchThrowableOfType(
            RecommendationPreferenceBusinessException.class,
            () -> service.savePreferences(USER_ID, request(null, null, null, null)));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode())
            .isEqualTo(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(recommendationPreferenceRepository);
    }

    @Test
    void 기존_행이_없으면_검증한_숫자를_Short로_변환해서_생성한다() {
        User user = givenActiveUser();

        service.savePreferences(USER_ID, request(19, 39, 130, 220));

        ArgumentCaptor<RecommendationPreference> captor = ArgumentCaptor.forClass(RecommendationPreference.class);
        verify(recommendationPreferenceRepository).save(captor.capture());
        RecommendationPreference preference = captor.getValue();
        assertThat(preference.getUser()).isSameAs(user);
        assertThat(preference.getMinAge()).isEqualTo((short) 19);
        assertThat(preference.getMaxAge()).isEqualTo((short) 39);
        assertThat(preference.getMinHeight()).isEqualTo((short) 130);
        assertThat(preference.getMaxHeight()).isEqualTo((short) 220);
        assertThat(preference.getCreatedAt()).isNotNull();
        assertThat(preference.getUpdatedAt()).isEqualTo(preference.getCreatedAt());
    }

    @Test
    void 최초_초기화_요청도_행을_생성하고_null_배열을_빈_배열로_바꾼다() {
        givenActiveUser();

        service.savePreferences(USER_ID,
            new RecommendationPreferenceSaveRequest(null, null, null, null, null, null, null));

        ArgumentCaptor<RecommendationPreference> captor = ArgumentCaptor.forClass(RecommendationPreference.class);
        verify(recommendationPreferenceRepository).save(captor.capture());
        RecommendationPreference preference = captor.getValue();
        assertThat(preference.getMinAge()).isNull();
        assertThat(preference.getMaxAge()).isNull();
        assertThat(preference.getMinHeight()).isNull();
        assertThat(preference.getMaxHeight()).isNull();
        assertThat(preference.getReligion()).isEmpty();
        assertThat(preference.getDrinking()).isEmpty();
        assertThat(preference.getSmoking()).isEmpty();
    }

    @Test
    void 기존_행이_있으면_전체_조건을_대체하고_새_행을_저장하지_않는다() {
        User user = givenActiveUser();
        RecommendationPreference preference = new RecommendationPreference(user, CREATED_AT);
        given(recommendationPreferenceRepository.findById(USER_ID)).willReturn(Optional.of(preference));
        RecommendationPreferenceSaveRequest request = new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180, List.of(Religion.NONE), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER));

        service.savePreferences(USER_ID, request);

        assertThat(preference.getMinAge()).isEqualTo((short) 25);
        assertThat(preference.getMaxAge()).isEqualTo((short) 30);
        assertThat(preference.getMinHeight()).isEqualTo((short) 160);
        assertThat(preference.getMaxHeight()).isEqualTo((short) 180);
        assertThat(preference.getReligion()).containsExactly(Religion.NONE);
        assertThat(preference.getDrinking()).containsExactly(Drinking.NEVER);
        assertThat(preference.getSmoking()).containsExactly(Smoking.NON_SMOKER);
        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isAfter(CREATED_AT);
        verify(recommendationPreferenceRepository, never()).save(any());
    }

    @Test
    void 같은_조건을_다른_배열_순서로_요청하면_수정_시각을_유지한다() {
        User user = givenActiveUser();
        RecommendationPreference preference = new RecommendationPreference(user,
            (short) 25, (short) 30, (short) 160, (short) 180,
            List.of(Religion.NONE, Religion.CATHOLIC), List.of(), List.of(), CREATED_AT);
        given(recommendationPreferenceRepository.findById(USER_ID)).willReturn(Optional.of(preference));

        service.savePreferences(USER_ID, new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180, List.of(Religion.CATHOLIC, Religion.NONE), List.of(), List.of()));

        assertThat(preference.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getReligion()).containsExactly(Religion.NONE, Religion.CATHOLIC);
        verify(recommendationPreferenceRepository, never()).save(any());
    }

    @Test
    void 초기화하면_기존_행과_생성_시각을_유지하고_조건만_비운다() {
        User user = givenActiveUser();
        RecommendationPreference preference = new RecommendationPreference(user,
            (short) 25, (short) 30, (short) 160, (short) 180,
            List.of(Religion.NONE), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), CREATED_AT);
        given(recommendationPreferenceRepository.findById(USER_ID)).willReturn(Optional.of(preference));

        service.savePreferences(USER_ID,
            new RecommendationPreferenceSaveRequest(null, null, null, null, null, null, null));

        assertThat(preference.getMinAge()).isNull();
        assertThat(preference.getMaxAge()).isNull();
        assertThat(preference.getMinHeight()).isNull();
        assertThat(preference.getMaxHeight()).isNull();
        assertThat(preference.getReligion()).isEmpty();
        assertThat(preference.getDrinking()).isEmpty();
        assertThat(preference.getSmoking()).isEmpty();
        assertThat(preference.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(preference.getUpdatedAt()).isAfter(CREATED_AT);
        verify(recommendationPreferenceRepository, never()).save(any());
    }

    private User givenActiveUser() {
        User user = mock(User.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        return user;
    }

    private User givenActiveUserForRead() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        return user;
    }

    private RecommendationPreferenceSaveRequest request(
        Integer minAge, Integer maxAge, Integer minHeight, Integer maxHeight) {
        return new RecommendationPreferenceSaveRequest(minAge, maxAge, minHeight, maxHeight,
            List.of(), List.of(), List.of());
    }
}
