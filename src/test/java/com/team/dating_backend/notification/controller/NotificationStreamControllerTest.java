package com.team.dating_backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationStreamExceptionHandler;
import com.team.dating_backend.notification.service.NotificationSubscriptionService;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class NotificationStreamControllerTest {

    private NotificationSubscriptionService service;
    private NotificationEmitterRegistry registry;
    private NotificationStreamExceptionHandler handler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationSubscriptionService.class);
        registry = new NotificationEmitterRegistry();
        handler = new NotificationStreamExceptionHandler(registry, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationStreamController(service))
            .setControllerAdvice(new GlobalExceptionHandler(), handler)
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 구독은_연결을_요청에_보관하고_초기_이벤트와_SSE_헤더를_반환한다() throws Exception {
        NotificationSseConnection connection = connection();
        registry.register(connection);
        new NotificationSseSender(registry).sendConnected(connection);
        given(service.subscribe(1L)).willReturn(connection);

        try {
            mockMvc.perform(get("/api/v1/notifications/stream")
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(request().attribute(NotificationStreamController.CONNECTION_ATTRIBUTE, connection))
                .andExpect(header().string("Cache-Control", "no-cache, no-store"))
                .andExpect(content().contentType("text/event-stream;charset=UTF-8"))
                .andExpect(content().string("event:connected\ndata:{\"connectionId\":\"first\"}\n\n"));
        } finally {
            connection.getEmitter().complete();
        }
    }

    @Test
    void ACTIVE_검증_실패는_SSE가_아닌_403_JSON_오류_코드로_반환한다() throws Exception {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(service).subscribe(1L);

        mockMvc.perform(get("/api/v1/notifications/stream")
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andExpect(request().asyncNotStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"errorCode\":\"REQUESTER_NOT_ACTIVE\"}"));
    }

    @Test
    void 미확정_초기화_실패는_기존_버퍼를_JSON으로_대체하고_비동기_요청을_완료한다() throws Exception {
        NotificationSseConnection connection = connection();
        registry.register(connection);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        request.setAttribute(NotificationStreamController.CONNECTION_ATTRIBUTE, connection);
        request.startAsync(request, response);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.getOutputStream().write("event:connected\n".getBytes(StandardCharsets.UTF_8));

        handler.handleStreamException(new IllegalStateException(), request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}");
        assertThat(request.isAsyncStarted()).isFalse();
        assertThat(connection.isClosed()).isTrue();
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void 이미_확정된_SSE_응답에는_JSON을_추가하거나_상태를_변경하지_않는다() throws Exception {
        NotificationSseConnection connection = connection();
        registry.register(connection);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(NotificationStreamController.CONNECTION_ATTRIBUTE, connection);
        response.getWriter().write("event:connected\n\n");
        response.flushBuffer();

        handler.handleStreamException(new IllegalStateException(), request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("event:connected\n\n");
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void 응답을_쓸_수_없는_단절_오류는_연결만_정리한다() {
        NotificationSseConnection connection = connection();
        registry.register(connection);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(NotificationStreamController.CONNECTION_ATTRIBUTE, connection);

        handler.handleStreamException(new AsyncRequestNotUsableException(null), request, response);

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void 오류_응답_판단은_진행_중인_전송이_끝날_때까지_기다린다() throws Exception {
        CountDownLatch removed = new CountDownLatch(1);
        NotificationEmitterRegistry concurrentRegistry = new NotificationEmitterRegistry() {
            @Override
            public void remove(NotificationSseConnection connection) {
                super.remove(connection);
                removed.countDown();
            }
        };
        NotificationStreamExceptionHandler concurrentHandler = new NotificationStreamExceptionHandler(
            concurrentRegistry, new ObjectMapper());
        NotificationSseConnection connection = connection();
        concurrentRegistry.register(connection);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(NotificationStreamController.CONNECTION_ATTRIBUTE, connection);
        response.getWriter().write("event:connected\n\n");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> handling;
            connection.lockForSend();
            try {
                handling = executor.submit(() -> concurrentHandler.handleStreamException(
                    new IllegalStateException(), request, response));
                assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> handling.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                response.flushBuffer();
            } finally {
                connection.unlockForSend();
            }
            handling.get(5, TimeUnit.SECONDS);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("event:connected\n\n");
            assertThat(concurrentRegistry.snapshotAll()).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private NotificationSseConnection connection() {
        return new NotificationSseConnection(1L, "first", new SseEmitter(30_000L), LocalDateTime.now());
    }

    private static class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                && parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
        }

        @Override
        public Object resolveArgument(
            MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
            return new ServiceAuthenticationPrincipal(1L);
        }
    }
}
