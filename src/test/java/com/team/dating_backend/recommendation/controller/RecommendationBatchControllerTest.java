package com.team.dating_backend.recommendation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.recommendation.dto.response.RecommendationBatchCreateResponse;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void 배치를_생성하면_201과_ID_생성_시각을_반환한다() throws Exception {
        given(service.createRecommendationBatch(5L)).willReturn(
            new RecommendationBatchCreateResponse(42L, LocalDateTime.of(2026, 9, 25, 12, 30)));

        mockMvc.perform(post("/api/v1/recommendation-batches"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("recommendation_batch_create_success"))
            .andExpect(jsonPath("$.data.batchId").isNumber())
            .andExpect(jsonPath("$.data.batchId").value(42))
            .andExpect(jsonPath("$.data.createdAt").value("2026-09-25T12:30:00"));
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
