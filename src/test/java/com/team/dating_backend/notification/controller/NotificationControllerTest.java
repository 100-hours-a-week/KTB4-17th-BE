package com.team.dating_backend.notification.controller;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class NotificationControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long NOTIFICATION_ID = 10L;
    private static final String DELETE_PATH = "/api/v1/notifications/{notificationId}";

    private NotificationDeleteService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationDeleteService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 삭제가_정상_종료되면_204와_빈_본문을_반환한다() throws Exception {
        mockMvc.perform(delete(DELETE_PATH, NOTIFICATION_ID))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(service).deleteNotification(USER_ID, NOTIFICATION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", " ", "0", "9223372036854775808"})
    void ID가_잘못되면_항목별_오류를_반환하고_Service를_호출하지_않는다(String id)
        throws Exception {
        mockMvc.perform(delete(DELETE_PATH, id))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_NOTIFICATION_ID\"}"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationErrorCode.class,
        names = {"REQUESTER_NOT_ACTIVE", "NOTIFICATION_ACCESS_DENIED"}
    )
    void 접근_거부는_403과_오류_코드만_반환한다(NotificationErrorCode errorCode)
        throws Exception {
        willThrow(new NotificationBusinessException(errorCode))
            .given(service).deleteNotification(USER_ID, NOTIFICATION_ID);

        mockMvc.perform(delete(DELETE_PATH, NOTIFICATION_ID))
            .andExpect(status().isForbidden())
            .andExpect(content().string("{\"errorCode\":\"" + errorCode.name() + "\"}"));
    }

    private static final class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

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
    }
}
