package com.team.dating_backend.recommendation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.recommendation.dto.request.RecommendationPreferenceSaveRequest;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceResponse;
import com.team.dating_backend.recommendation.enums.RecommendationPreferenceErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationPreferenceBusinessException;
import com.team.dating_backend.recommendation.service.RecommendationPreferenceService;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(RecommendationPreferenceController.class)
@Import(
    {
        SecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        GlobalExceptionHandler.class
    }
)
class RecommendationPreferenceControllerTest {

    private static final Long USER_ID = 42L;
    private static final String ENDPOINT = "/api/v1/users/me/preferences";
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RecommendationPreferenceService recommendationPreferenceService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        given(securityProperties.getAllowedOrigins()).willReturn(List.of());
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(USER_ID);
    }

    @Test
    void 조회는_200과_일곱_조건이_있는_기본_응답을_반환한다() throws Exception {
        given(recommendationPreferenceService.getPreferences(USER_ID))
            .willReturn(new RecommendationPreferenceGetResponse(RecommendationPreferenceResponse.unrestricted()));

        mockMvc.perform(authenticatedGet())
            .andExpect(status().isOk())
            .andExpect(content().string("{\"message\":\"preference_get_success\",\"data\":{"
                + "\"preference\":{\"minAge\":null,\"maxAge\":null,\"minHeight\":null,"
                + "\"maxHeight\":null,\"religion\":[],\"drinking\":[],\"smoking\":[]}}}"));

        verify(recommendationPreferenceService).getPreferences(USER_ID);
    }

    @Test
    void 저장과_같은_요청의_재요청은_204와_빈_본문을_반환한다() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(authenticatedPut().content(VALID_REQUEST))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        }

        verify(recommendationPreferenceService, times(2)).savePreferences(USER_ID,
            new RecommendationPreferenceSaveRequest(25, 30, 160, 180,
                List.of(Religion.NONE, Religion.CATHOLIC),
                List.of(Drinking.NEVER, Drinking.SOCIAL),
                List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL)));
    }

    @Test
    void Jackson_기본_정책으로_변환한_값을_Service에_전달한다() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.put("minAge", "25");
        body.put("maxAge", 30.5);
        body.set("religion", objectMapper.readTree("[0]"));
        body.set("drinking", objectMapper.readTree("[\"0\"]"));

        mockMvc.perform(authenticatedPut().content(body.toString()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(recommendationPreferenceService).savePreferences(USER_ID,
            new RecommendationPreferenceSaveRequest(25, 30, 160, 180,
                List.of(Religion.CATHOLIC),
                List.of(Drinking.NEVER),
                List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL)));
    }

    @Test
    void 초기화는_전체_필드를_null로_전달해서_204로_처리한다() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        for (String field : List.of("minAge", "maxAge", "minHeight", "maxHeight",
            "religion", "drinking", "smoking")) {
            body.putNull(field);
        }

        mockMvc.perform(authenticatedPut().content(body.toString()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(recommendationPreferenceService).savePreferences(USER_ID,
            new RecommendationPreferenceSaveRequest(null, null, null, null, null, null, null));
    }

    @Test
    void 요청에_회원_ID를_추가해도_인증된_사용자의_ID를_전달한다() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.put("userId", 999L);

        mockMvc.perform(authenticatedPut().content(body.toString()))
            .andExpect(status().isNoContent());

        verify(recommendationPreferenceService).savePreferences(org.mockito.ArgumentMatchers.eq(USER_ID), any());
    }

    @Test
    void 조회에_인증정보가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get(ENDPOINT))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    @Test
    void 잘못된_저장_본문이어도_인증정보가_없으면_먼저_401을_반환한다() throws Exception {
        mockMvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    @Test
    void 유효하지_않은_토큰이면_401을_반환한다() throws Exception {
        given(jwtService.parseServiceAuthToken("bad-token")).willThrow(new JwtException("invalid"));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer bad-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "minAge", "maxAge", "minHeight", "maxHeight", "religion", "drinking", "smoking"
        }
    )
    void 필드가_누락되면_400을_반환하고_Service를_호출하지_않는다(String field) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.remove(field);

        mockMvc.perform(authenticatedPut().content(body.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "[]", "{", "\"text\""})
    void JSON_본문이_없거나_객체_형식이_잘못되면_400을_반환한다(String body) throws Exception {
        mockMvc.perform(authenticatedPut().content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    @ParameterizedTest
    @EnumSource(RecommendationPreferenceErrorCode.class)
    void 저장_Service의_오류는_구체적인_코드와_정의한_상태로_반환한다(
        RecommendationPreferenceErrorCode errorCode) throws Exception {
        willThrow(new RecommendationPreferenceBusinessException(errorCode))
            .given(recommendationPreferenceService).savePreferences(any(), any());

        mockMvc.perform(authenticatedPut().content(VALID_REQUEST))
            .andExpect(status().is(errorCode.status().value()))
            .andExpect(content().string("{\"errorCode\":\"" + errorCode.name() + "\"}"));
    }

    @Test
    void 조회_사용자가_ACTIVE가_아니면_403을_반환한다() throws Exception {
        given(recommendationPreferenceService.getPreferences(USER_ID))
            .willThrow(
                new RecommendationPreferenceBusinessException(RecommendationPreferenceErrorCode.REQUESTER_NOT_ACTIVE));

        mockMvc.perform(authenticatedGet())
            .andExpect(status().isForbidden())
            .andExpect(content().string("{\"errorCode\":\"REQUESTER_NOT_ACTIVE\"}"));
    }

    @Test
    void 저장_중_DB_오류는_500과_서버_오류_코드만_반환한다() throws Exception {
        willThrow(new DataAccessResourceFailureException("database unavailable"))
            .given(recommendationPreferenceService).savePreferences(any(), any());

        mockMvc.perform(authenticatedPut().content(VALID_REQUEST))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}"));
    }

    @ParameterizedTest
    @MethodSource("invalidJsonValues")
    void Jackson으로_변환할_수_없는_값은_400으로_거절한다(
        String field, String value) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        body.set(field, objectMapper.readTree(value));

        mockMvc.perform(authenticatedPut().content(body.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verifyNoInteractions(recommendationPreferenceService);
    }

    private static Stream<Arguments> invalidJsonValues() {
        Stream<Arguments> numbers = Stream.of("minAge", "maxAge", "minHeight", "maxHeight")
            .flatMap(field -> Stream.of("true", "[]", "{}", "2147483648")
                .map(value -> Arguments.of(field, value)));
        Stream<Arguments> selections = Stream.of("religion", "drinking", "smoking")
            .flatMap(field -> Stream.of("[true]", "[{}]", "[\"UNKNOWN\"]",
                "[99]", "\"NONE\"", "\"\"", "{}")
                .map(value -> Arguments.of(field, value)));
        return Stream.concat(numbers, selections);
    }

    private MockHttpServletRequestBuilder authenticatedGet() {
        return get(ENDPOINT).header("Authorization", "Bearer service-token");
    }

    private MockHttpServletRequestBuilder authenticatedPut() {
        return put(ENDPOINT).header("Authorization", "Bearer service-token")
            .contentType(MediaType.APPLICATION_JSON);
    }
}
