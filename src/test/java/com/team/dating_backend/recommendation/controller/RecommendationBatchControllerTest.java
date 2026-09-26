package com.team.dating_backend.recommendation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.recommendation.dto.response.RecommendationBatchCreateResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationBatchGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationCandidateResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemPageInfo;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemsGetResponse;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.RecommendationBatchGetService;
import com.team.dating_backend.recommendation.service.RecommendationItemGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(controllers = RecommendationBatchController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(RecommendationBatchControllerTest.TestAuthenticationPrincipalConfig.class)
class RecommendationBatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationBatchCreateService service;

    @MockitoBean
    private RecommendationBatchGetService recommendationBatchGetService;

    @MockitoBean
    private RecommendationItemGetService recommendationItemGetService;

    @Test
    void 배치를_생성하면_201과_ID_생성_시각을_반환한다() throws Exception {
        given(service.createRecommendationBatch(5L)).willReturn(
            Optional.of(
                new RecommendationBatchCreateResponse(
                    42L, LocalDateTime.of(2026, 9, 25, 12, 30))));

        mockMvc.perform(post("/api/v1/recommendation-batches"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("recommendation_batch_create_success"))
            .andExpect(jsonPath("$.data.batchId").isNumber())
            .andExpect(jsonPath("$.data.batchId").value(42))
            .andExpect(jsonPath("$.data.createdAt").value("2026-09-25T12:30:00"));
        verify(service).createRecommendationBatch(5L);
    }

    @Test
    void 후보가_없으면_204와_빈_본문을_반환한다() throws Exception {
        given(service.createRecommendationBatch(5L)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/recommendation-batches"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        verify(service).createRecommendationBatch(5L);
    }

    @Test
    void 권한이_없는_사용자의_요청에는_403을_반환한다() throws Exception {
        given(service.createRecommendationBatch(5L)).willThrow(
            new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE));

        mockMvc.perform(post("/api/v1/recommendation-batches"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("REQUESTER_NOT_ACTIVE"));
    }

    @Test
    void 활성_배치가_있으면_200과_배치_ID를_반환한다() throws Exception {
        given(recommendationBatchGetService.getActiveRecommendationBatch(5L))
            .willReturn(new RecommendationBatchGetResponse(42L));

        mockMvc.perform(get("/api/v1/recommendation-batches/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("recommendation_batch_get_success"))
            .andExpect(jsonPath("$.data.batchId").isNumber())
            .andExpect(jsonPath("$.data.batchId").value(42))
            .andExpect(jsonPath("$.data.generationState").doesNotExist())
            .andExpect(jsonPath("$.data.createdAt").doesNotExist())
            .andExpect(jsonPath("$.data.emptyState").doesNotExist());
        verify(recommendationBatchGetService).getActiveRecommendationBatch(5L);
    }

    @Test
    void 활성_배치가_없으면_200과_배치_ID로_null을_반환한다() throws Exception {
        given(recommendationBatchGetService.getActiveRecommendationBatch(5L))
            .willReturn(new RecommendationBatchGetResponse(null));

        mockMvc.perform(get("/api/v1/recommendation-batches/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("recommendation_batch_get_success"))
            .andExpect(jsonPath("$.data.batchId").value(nullValue()));
        verify(recommendationBatchGetService).getActiveRecommendationBatch(5L);
    }

    @Test
    void 비활성_사용자의_조회는_403을_반환한다() throws Exception {
        given(recommendationBatchGetService.getActiveRecommendationBatch(5L)).willThrow(
            new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE));

        mockMvc.perform(get("/api/v1/recommendation-batches/active"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("REQUESTER_NOT_ACTIVE"));
    }

    @Test
    void 추천_Item_목록은_후보_정보와_페이지_정보를_반환한다() throws Exception {
        RecommendationCandidateResponse candidate = new RecommendationCandidateResponse(
            21L, "하리", 29, "개발자", "서울특별시 강남구", null);
        given(recommendationItemGetService.getRecommendationItems(5L, 42L, 100L))
            .willReturn(new RecommendationItemsGetResponse(
                List.of(new RecommendationItemResponse(101L, candidate)),
                new RecommendationItemPageInfo(101L, true, 42L)));

        mockMvc.perform(get("/api/v1/recommendation-batches/42/items")
            .param("cursor", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("recommendation_items_get_success"))
            .andExpect(jsonPath("$.data.items[0].itemId").value(101))
            .andExpect(jsonPath("$.data.items[0].candidate.memberId").value(21))
            .andExpect(jsonPath("$.data.items[0].candidate.nickname").value("하리"))
            .andExpect(jsonPath("$.data.items[0].candidate.age").value(29))
            .andExpect(jsonPath("$.data.items[0].candidate.job").value("개발자"))
            .andExpect(jsonPath("$.data.items[0].candidate.region").value("서울특별시 강남구"))
            .andExpect(jsonPath("$.data.items[0].candidate.mbti").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].capabilities").doesNotExist())
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(101))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(true))
            .andExpect(jsonPath("$.data.pageInfo.batchId").value(42));
    }

    @Test
    void 추천_Item_목록의_사용할_수_없는_배치는_404를_반환한다() throws Exception {
        given(recommendationItemGetService.getRecommendationItems(5L, 42L, null))
            .willThrow(new RecommendationBusinessException(
                RecommendationErrorCode.RESOURCE_NOT_AVAILABLE));

        mockMvc.perform(get("/api/v1/recommendation-batches/42/items"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void 추천_Item_목록_비활성_요청자는_403을_반환한다() throws Exception {
        given(recommendationItemGetService.getRecommendationItems(5L, 42L, null))
            .willThrow(new RecommendationBusinessException(
                RecommendationErrorCode.REQUESTER_NOT_ACTIVE));

        mockMvc.perform(get("/api/v1/recommendation-batches/42/items"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("REQUESTER_NOT_ACTIVE"));
    }

    @Test
    void 추천_Item_목록의_양수가_아닌_커서는_400을_반환한다() throws Exception {
        given(recommendationItemGetService.getRecommendationItems(5L, 42L, 0L))
            .willThrow(new RequestValidationException(
                List.of(new FieldErrorResponse("cursor", "must be a positive item ID"))));

        mockMvc.perform(get("/api/v1/recommendation-batches/42/items").param("cursor", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    void 추천_Item_목록의_잘못된_쿼리_값은_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/recommendation-batches/42/items").param("cursor", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType().equals(ServiceAuthenticationPrincipal.class);
                }

                @Override
                public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                    return new ServiceAuthenticationPrincipal(5L);
                }
            });
        }
    }
}
