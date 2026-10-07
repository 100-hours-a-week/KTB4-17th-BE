package com.team.dating_backend.profile.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.profile.dto.response.MemberProfileResponse;
import com.team.dating_backend.profile.service.MemberProfileGetService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = MemberProfileController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(MemberProfileControllerTest.TestAuthenticationPrincipalConfig.class)
class MemberProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberProfileGetService memberProfileGetService;

    @Test
    void 추천_회원_프로필을_반환한다() throws Exception {
        given(memberProfileGetService.getMemberProfile(5L, 21L))
            .willReturn(new MemberProfileResponse(
                21L,
                "하리",
                29,
                "개발자",
                "서울특별시 강남구",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new MemberProfileResponse.Image(
                    701L, (short) 1, "https://example.com/701"))));

        mockMvc.perform(get("/api/v1/users/21/profile"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.message").value("member_profile_get_success"))
            .andExpect(jsonPath("$.data.memberId").value(21))
            .andExpect(jsonPath("$.data.images[0].fileId").value(701))
            .andExpect(jsonPath("$.data.images[0].displayOrder").value(1))
            .andExpect(jsonPath("$.data.images[0].imageUrl")
                .value("https://example.com/701"))
            .andExpect(jsonPath("$.data.images[0].expiresAt").doesNotExist());
        verify(memberProfileGetService).getMemberProfile(5L, 21L);
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
                    return new ServiceAuthenticationPrincipal(5L);
                }
            });
        }
    }
}
