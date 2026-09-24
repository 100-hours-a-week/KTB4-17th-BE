package com.team.dating_backend.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveProfileResponse;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResponse;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResult;
import com.team.dating_backend.onboarding.exception.NicknameAlreadyInUseException;
import com.team.dating_backend.onboarding.service.ProfileSaveService;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

@WebMvcTest(controllers = ProfileSaveController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(ProfileSaveControllerTest.TestAuthenticationPrincipalConfig.class)
class ProfileSaveControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileSaveService profileSaveService;

    @Test
    void 프로필을_최초로_저장하면_201과_저장된_프로필을_반환한다() throws Exception {
        ProfileSaveProfileResponse profile = new ProfileSaveProfileResponse(
            37L,
            "하리",
            (short) 175,
            BodyType.AVERAGE,
            EducationLevel.BACHELOR,
            "개발자",
            null,
            null,
            null,
            null);
        given(profileSaveService.saveProfile(any(), any(ProfileSaveRequest.class)))
            .willReturn(new ProfileSaveResult(true, new ProfileSaveResponse(profile)));

        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("profile_save_success"))
            .andExpect(jsonPath("$.data.profile.activityRegionId").value(37))
            .andExpect(jsonPath("$.data.profile.nickname").value("하리"))
            .andExpect(jsonPath("$.data.profile.height").value(175))
            .andExpect(jsonPath("$.data.profile.bodyType").value("AVERAGE"))
            .andExpect(jsonPath("$.data.profile.educationLevel").value("BACHELOR"))
            .andExpect(jsonPath("$.data.profile.job").value("개발자"))
            .andExpect(jsonPath("$.data.profile.religion").isEmpty())
            .andExpect(jsonPath("$.data.profile.mbti").isEmpty())
            .andExpect(jsonPath("$.data.profile.drinking").isEmpty())
            .andExpect(jsonPath("$.data.profile.smoking").isEmpty());

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(profileSaveService)
            .saveProfile(userIdCaptor.capture(), any(ProfileSaveRequest.class));
        assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
    }

    @Test
    void 기존_프로필을_수정하면_204와_빈_본문을_반환한다() throws Exception {
        given(profileSaveService.saveProfile(any(), any(ProfileSaveRequest.class)))
            .willReturn(new ProfileSaveResult(false, null));

        mockMvc.perform(authenticatedPut().contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(profileSaveService).saveProfile(any(), any(ProfileSaveRequest.class));
    }

    @Test
    void 닉네임_형식이_올바르지_않으면_400과_필드_오류를_반환한다() throws Exception {
        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"닉네임!\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("nickname"))
            .andExpect(
                jsonPath("$.errors[0].reason")
                    .value(
                        "must be 2 to 10 characters using Korean, English letters, or digits"));

        verifyNoInteractions(profileSaveService);
    }

    @Test
    void 키가_허용_범위를_벗어나면_400과_필드_오류를_반환한다() throws Exception {
        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"height\":221}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("height"))
            .andExpect(jsonPath("$.errors[0].reason").value("must be between 130 and 220"));

        verifyNoInteractions(profileSaveService);
    }

    @Test
    void 직업이_공백이면_400과_필드_오류를_반환한다() throws Exception {
        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("job"))
            .andExpect(
                jsonPath("$.errors[0].reason")
                    .value("must be non-blank and at most 50 characters"));

        verifyNoInteractions(profileSaveService);
    }

    @Test
    void 현재_enum에_없는_코드이면_400을_반환한다() throws Exception {
        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyType\":\"NORMAL\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(profileSaveService);
    }

    @Test
    void 존재하지_않는_활동_지역이면_400과_필드_오류를_반환한다() throws Exception {
        given(profileSaveService.saveProfile(any(), any(ProfileSaveRequest.class)))
            .willThrow(
                new RequestValidationException(
                    List.of(
                        new FieldErrorResponse(
                            "activityRegionId",
                            "must reference an existing activity region"))));

        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activityRegionId\":999}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("activityRegionId"))
            .andExpect(
                jsonPath("$.errors[0].reason")
                    .value("must reference an existing activity region"));
    }

    @Test
    void 중복된_닉네임이면_409를_반환한다() throws Exception {
        given(profileSaveService.saveProfile(any(), any(ProfileSaveRequest.class)))
            .willThrow(new NicknameAlreadyInUseException("하리"));

        mockMvc.perform(
            authenticatedPut()
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"하리\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("NICKNAME_ALREADY_IN_USE"))
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    private String validRequest() {
        return """
            {
              "activityRegionId": 37,
              "nickname": "하리",
              "height": 175,
              "bodyType": "AVERAGE",
              "educationLevel": "BACHELOR",
              "job": "개발자",
              "religion": null,
              "mbti": null,
              "drinking": null,
              "smoking": null
            }
            """;
    }

    private MockHttpServletRequestBuilder authenticatedPut() {
        return put("/api/v1/users/me/profile");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(
                new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                            && parameter
                                .getParameterType()
                                .equals(ServiceAuthenticationPrincipal.class);
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
