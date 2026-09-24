package com.team.dating_backend.matching.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(controllers = LikeController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(LikeControllerTest.TestAuthenticationPrincipalConfig.class)
class LikeControllerTest {

    private static final Long AUTHENTICATED_MEMBER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeSendService likeSendService;

    @Test
    void 로그인한_사용자가_좋아요를_전송하면_201과_PENDING을_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willReturn(new LikeCreateResponse(10L, LikeStatus.PENDING));

        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("like_create_success"))
            .andExpect(jsonPath("$.data.likeId").value(10))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(likeSendService).sendLike(1L, 2L);
    }

    @Test
    void 수신자_ID가_유효하지_않으면_400을_반환한다() throws Exception {
        mockMvc.perform(authenticatedPost("{\"receiverId\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(likeSendService);
    }

    @Test
    void 중복_PENDING은_409를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willThrow(new LikeBusinessException(LikeErrorCode.DUPLICATE_PENDING_LIKE));
        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_PENDING_LIKE"));
    }

    @Test
    void 사용할_수_없는_수신자는_404를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willThrow(new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void 자기_자신에게_전송하면_422를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 1L))
            .willThrow(new LikeBusinessException(LikeErrorCode.SELF_LIKE_NOT_ALLOWED));
        mockMvc.perform(authenticatedPost("{\"receiverId\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("SELF_LIKE_NOT_ALLOWED"));
    }

    private MockHttpServletRequestBuilder authenticatedPost(String body) {
        return post("/api/v1/likes")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
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
                    return new ServiceAuthenticationPrincipal(AUTHENTICATED_MEMBER_ID);
                }
            });
        }
    }
}
