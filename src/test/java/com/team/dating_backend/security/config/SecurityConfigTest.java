package com.team.dating_backend.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.notification.controller.NotificationController;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
import com.team.dating_backend.notification.service.NotificationUnreadCountService;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.UserAuthenticationService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = {SecurityConfigTest.SecurityProbeController.class, NotificationController.class})
@Import(
    {
        SecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityConfigTest.SecurityProbeController.class,
        NotificationAccessService.class,
        NotificationListService.class,
        NotificationUnreadCountService.class
    }
)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy securityFilterChain;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SecurityProperties securityProperties;

    @MockitoBean
    private UserAuthenticationService userAuthenticationService;

    @MockitoBean
    private NotificationEmitterRegistry notificationEmitterRegistry;

    @MockitoBean
    private NotificationDeleteService notificationDeleteService;

    @MockitoBean
    private NotificationRepository notificationRepository;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        given(securityProperties.getAllowedOrigins()).willReturn(List.of("http://localhost:5173"));
        given(userAuthenticationService.isAuthenticationAllowed(42L)).willReturn(true);
    }

    @Test
    void 보호_API에_인증정보가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/security/protected"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void OAuth_공개_API는_인증없이_접근할_수_있다() throws Exception {
        mockMvc.perform(get("/api/v1/auth/public")).andExpect(status().isOk());
    }

    @Test
    void 유효한_Bearer_토큰이면_현재사용자_ID를_Controller에서_사용할_수_있다() throws Exception {
        // given
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);

        // when & then
        mockMvc.perform(
            get("/api/v1/security/protected")
                .header("Authorization", "Bearer service-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void 쿠키에만_ACCESS_TOKEN이_있으면_보호_API는_401을_반환한다() throws Exception {
        mockMvc.perform(
            get("/api/v1/security/protected")
                .cookie(new jakarta.servlet.http.Cookie("ACCESS_TOKEN", "service-token")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF_토큰없이_Bearer_인증_POST_API를_호출할_수_있다() throws Exception {
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);

        mockMvc.perform(
            post("/api/v1/security/protected")
                .header("Authorization", "Bearer service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void CORS_설정은_허용된_Origin을_등록한다() {
        SecurityConfig securityConfig = new SecurityConfig(
            jwtService,
            userAuthenticationService,
            new ApiAuthenticationEntryPoint(new ObjectMapper()),
            new ApiAccessDeniedHandler(new ObjectMapper()),
            securityProperties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/security/protected");

        CorsConfiguration configuration = securityConfig.corsConfigurationSource()
            .getCorsConfiguration(request);

        assertEquals(List.of("http://localhost:5173"), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(configuration.getAllowedHeaders().contains("Authorization"));
        assertTrue(configuration.getAllowCredentials());
    }

    @Test
    void 최초_SSE_구독_요청은_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/stream"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/notifications", "/api/v1/notifications/unread-count"})
    void 알림_조회는_미인증_요청을_401로_차단하고_DB를_조회하지_않는다(String path) throws Exception {
        mockMvc.perform(get(path))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(userRepository, notificationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/notifications", "/api/v1/notifications/unread-count"})
    void 알림_조회는_유효하지_않은_토큰을_401로_차단한다(String path) throws Exception {
        given(jwtService.parseServiceAuthToken("invalid-token")).willThrow(new JwtException("invalid"));

        mockMvc.perform(get(path)
            .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(userRepository, notificationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/notifications", "/api/v1/notifications/unread-count"})
    void 인증_계층에서_회원_접근을_거부하면_조회_Service_진입_전에_401을_반환한다(String path) throws Exception {
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);
        given(userAuthenticationService.isAuthenticationAllowed(42L)).willReturn(false);

        mockMvc.perform(get(path)
            .header("Authorization", "Bearer service-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"errorCode\":\"AUTH_REQUIRED\"}"));

        verifyNoInteractions(userRepository, notificationRepository);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "/api/v1/notifications,ONBOARDING",
            "/api/v1/notifications,SUSPENDED",
            "/api/v1/notifications/unread-count,ONBOARDING",
            "/api/v1/notifications/unread-count,SUSPENDED"
        }
    )
    void 인증을_통과한_비ACTIVE_회원은_알림_조회_전에_403으로_차단한다(String path, UserStatus status) throws Exception {
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        mockMvc.perform(get(path)
            .header("Authorization", "Bearer service-token"))
            .andExpect(status().isForbidden())
            .andExpect(content().string("{\"errorCode\":\"REQUESTER_NOT_ACTIVE\"}"));

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void ACTIVE_회원의_목록_조회는_Principal의_ID로_Service까지_연결된다() throws Exception {
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);
        User user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(42L)).willReturn(Optional.of(user));
        given(notificationRepository.findVisibleByUserId(42L, PageRequest.of(0, 21)))
            .willReturn(List.of());

        mockMvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer service-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("notification_list_success"))
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));

        verify(notificationRepository).findVisibleByUserId(42L, PageRequest.of(0, 21));
    }

    @Test
    void ACTIVE_회원의_미읽음_집계는_Principal의_ID로_실제_Service에_연결된다() throws Exception {
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);
        User user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(42L)).willReturn(Optional.of(user));
        given(notificationRepository.countVisibleUnreadByUserId(42L)).willReturn(3L);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
            .header("Authorization", "Bearer service-token"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"message":"notification_unread_count_success","data":{"count":3}}
                """));

        verify(notificationRepository).countVisibleUnreadByUserId(42L);
        verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    void SSE의_GET_ASYNC_재디스패치만_인증없이_후속_처리를_허용한다() throws Exception {
        assertEquals(204, asyncStatus("GET", "/api/v1/notifications/stream"));
        assertEquals(401, asyncStatus("GET", "/api/v1/security/protected"));
        assertEquals(401, asyncStatus("POST", "/api/v1/notifications/stream"));
        assertEquals(401, asyncStatus("GET", "/api/v1/notifications"));
        assertEquals(401, asyncStatus("GET", "/api/v1/notifications/unread-count"));
    }

    private int asyncStatus(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        securityFilterChain.doFilter(request, response,
            (currentRequest, currentResponse) -> ((HttpServletResponse) currentResponse).setStatus(204));
        return response.getStatus();
    }

    @Test
    void WebSocket_핸드셰이크는_STOMP_인증전에_허용된다() throws Exception {
        mockMvc.perform(
            get("/ws/chat")
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ=="))
            .andExpect(status().isOk());
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/v1/notifications/stream")
        ResponseEntity<Void> notificationStreamProbe() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/v1/security/protected")
        Map<String, Long> protectedEndpoint(
            @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
            return Map.of("userId", principal.userId());
        }

        @GetMapping("/api/v1/auth/public")
        ResponseEntity<Void> publicEndpoint() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/v1/security/protected")
        Map<String, Long> protectedPostEndpoint(
            @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
            return Map.of("userId", principal.userId());
        }

        @GetMapping("/ws/chat")
        ResponseEntity<Void> websocketHandshakeProbe() {
            return ResponseEntity.ok().build();
        }
    }
}
