package com.team.dating_backend.profile.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.dto.response.ProfileImageResponse;
import com.team.dating_backend.profile.dto.response.ProfileImageSaveResponse;
import com.team.dating_backend.profile.service.ProfileImageSaveService;
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
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = ProfileImageController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProfileImageControllerTest.TestAuthenticationPrincipalConfig.class)
class ProfileImageControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileImageSaveService profileImageSaveService;

    @Test
    void 프로필_이미지_전체상태를_저장하고_확정된_순서를_반환한다() throws Exception {
        given(profileImageSaveService.saveProfileImages(any(), any(ProfileImageSaveRequest.class)))
            .willReturn(new ProfileImageSaveResponse(List.of(
                new ProfileImageResponse(701L, (short) 1, false),
                new ProfileImageResponse(704L, (short) 2, true))));

        mockMvc.perform(put("/api/v1/users/me/profile/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "images": [
                    {"fileId": 701, "isFrontal": false},
                    {"fileId": 704, "isFrontal": true}
                  ]
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("profile_image_save_success"))
            .andExpect(jsonPath("$.data.images[0].fileId").value(701))
            .andExpect(jsonPath("$.data.images[0].displayOrder").value(1))
            .andExpect(jsonPath("$.data.images[0].isFrontal").value(false))
            .andExpect(jsonPath("$.data.images[0].profileImageId").doesNotExist())
            .andExpect(jsonPath("$.data.images[1].fileId").value(704))
            .andExpect(jsonPath("$.data.images[1].displayOrder").value(2))
            .andExpect(jsonPath("$.data.images[1].isFrontal").value(true));

        verify(profileImageSaveService).saveProfileImages(
            any(),
            any(ProfileImageSaveRequest.class));
    }

    @Test
    void 사진이_7장이면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/profile/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "images": [
                    {"fileId": 1, "isFrontal": true},
                    {"fileId": 2, "isFrontal": false},
                    {"fileId": 3, "isFrontal": false},
                    {"fileId": 4, "isFrontal": false},
                    {"fileId": 5, "isFrontal": false},
                    {"fileId": 6, "isFrontal": false},
                    {"fileId": 7, "isFrontal": false}
                  ]
                }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verifyNoInteractions(profileImageSaveService);
    }

    @Test
    void isFrontal이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/profile/images")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"images":[{"fileId":701}]}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verifyNoInteractions(profileImageSaveService);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
                }

                @Override
                public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                    return new ServiceAuthenticationPrincipal(USER_ID);
                }
            });
        }
    }
}
