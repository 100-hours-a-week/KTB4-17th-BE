package com.team.dating_backend.notification.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
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
    private NotificationListService listService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationDeleteService.class);
        listService = mock(NotificationListService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service, listService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 조회_조건_생략은_전체_첫_페이지_20건으로_해석하고_빈_목록도_200을_반환한다() throws Exception {
        given(listService.getNotifications(USER_ID, NotificationCategory.ALL, null, 20))
            .willReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"message":"notification_list_success","data":{
                  "items":[],"pageInfo":{"nextCursor":null,"hasNext":false}}}
                """));

        verify(listService).getNotifications(USER_ID, NotificationCategory.ALL, null, 20);
        verifyNoInteractions(service);
    }

    @Test
    void 저장_데이터와_null_필드를_성공_응답에_포함한다() throws Exception {
        given(listService.getNotifications(USER_ID, NotificationCategory.SYSTEM, 100L, 1))
            .willReturn(new NotificationListResponse(
                List.of(new NotificationListResponse.Item(
                    95L, "AI_PRACTICE_CHAT_COMPLETED", "저장된 제목", "저장된 본문", null, null,
                    null, LocalDateTime.of(2026, 10, 17, 12, 0, 0, 123456000))),
                new NotificationListResponse.PageInfo(95L, true)));

        mockMvc.perform(get("/api/v1/notifications")
            .param("category", "SYSTEM").param("cursor", "100").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"message":"notification_list_success","data":{
                  "items":[{"notificationId":95,"type":"AI_PRACTICE_CHAT_COMPLETED",
                    "title":"저장된 제목","body":"저장된 본문","targetType":null,
                    "targetId":null,"readAt":null,"createdAt":"2026-10-17T12:00:00.123456"}],
                  "pageInfo":{"nextCursor":95,"hasNext":true}}}
                """));

        verify(listService).getNotifications(USER_ID, NotificationCategory.SYSTEM, 100L, 1);
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void 모든_정의된_카테고리를_허용한다(NotificationCategory category) throws Exception {
        given(listService.getNotifications(USER_ID, category, null, 20)).willReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/notifications").param("category", category.name())
            .param("size", "20"))
            .andExpect(status().isOk());

        verify(listService).getNotifications(USER_ID, category, null, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "interest", "UNKNOWN", " ALL"})
    void 잘못된_카테고리는_기본값으로_보정하지_않는다(String category) throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("category", category))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_NOTIFICATION_CATEGORY\"}"));

        verifyNoInteractions(listService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "0", "-1", "1.5", " 1", "9223372036854775808"})
    void 잘못된_커서는_항목별_오류를_반환한다(String cursor) throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("cursor", cursor))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_NOTIFICATION_CURSOR\"}"));

        verifyNoInteractions(listService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "0", "-1", "21", "1.5", " 1", "2147483648"})
    void 잘못된_조회_크기는_항목별_오류를_반환한다(String size) throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("size", size))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_NOTIFICATION_PAGE_SIZE\"}"));

        verifyNoInteractions(listService);
    }

    @Test
    void 양수_Long_최댓값도_커서로_전달한다() throws Exception {
        given(listService.getNotifications(USER_ID, NotificationCategory.ALL, Long.MAX_VALUE, 20))
            .willReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/notifications").param("cursor", Long.toString(Long.MAX_VALUE)))
            .andExpect(status().isOk());

        verify(listService).getNotifications(USER_ID, NotificationCategory.ALL, Long.MAX_VALUE, 20);
    }

    @Test
    void 목록_접근_거부는_403과_오류_코드만_반환한다() throws Exception {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(listService).getNotifications(USER_ID, NotificationCategory.ALL, null, 20);

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isForbidden())
            .andExpect(content().string("{\"errorCode\":\"REQUESTER_NOT_ACTIVE\"}"));
    }

    @Test
    void 목록_조회_실패는_빈_목록이_아닌_500을_반환한다() throws Exception {
        willThrow(new DataAccessResourceFailureException("조회 실패"))
            .given(listService).getNotifications(USER_ID, NotificationCategory.ALL, null, 20);

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}"));
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

    private NotificationListResponse emptyResponse() {
        return new NotificationListResponse(List.of(), new NotificationListResponse.PageInfo(null, false));
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
