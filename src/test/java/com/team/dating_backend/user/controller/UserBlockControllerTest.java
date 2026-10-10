package com.team.dating_backend.user.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.dto.response.UserBlockResponse;
import com.team.dating_backend.user.service.UserBlockService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(
    controllers = UserBlockController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(UserBlockControllerTest.TestAuthenticationPrincipalConfig.class)
class UserBlockControllerTest {

    private static final Long AUTHENTICATED_USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final LocalDateTime BLOCKED_AT = LocalDateTime.of(2026, 10, 10, 12, 30);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserBlockService userBlockService;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 신규_여부에_따라_201_또는_200을_반환한다(boolean created) throws Exception {
        given(userBlockService.block(AUTHENTICATED_USER_ID, TARGET_USER_ID))
            .willReturn(result(created));

        mockMvc.perform(put("/api/v1/users/me/blocks/{targetUserId}", TARGET_USER_ID))
            .andExpect(status().is(created ? 201 : 200))
            .andExpect(jsonPath("$.message").value("user_block_success"))
            .andExpect(jsonPath("$.data.targetUserId").value(TARGET_USER_ID))
            .andExpect(jsonPath("$.data.blockedAt").value("2026-10-10T12:30:00"));
        verify(userBlockService).block(AUTHENTICATED_USER_ID, TARGET_USER_ID);
    }

    @Test
    void 상대_회원_ID_형식이_잘못되면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/blocks/not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(userBlockService);
    }

    private UserBlockResult result(boolean created) {
        return new UserBlockResult(
            new UserBlockResponse(TARGET_USER_ID, BLOCKED_AT), created);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType()
                            .equals(ServiceAuthenticationPrincipal.class);
                }

                @Override
                public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                    return new ServiceAuthenticationPrincipal(AUTHENTICATED_USER_ID);
                }
            });
        }
    }
}
