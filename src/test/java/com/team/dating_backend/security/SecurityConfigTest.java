package com.team.dating_backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.security.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = SecurityConfigTest.SecurityProbeController.class)
@Import({
    SecurityConfig.class,
    ApiAuthenticationEntryPoint.class,
    ApiAccessDeniedHandler.class,
    SecurityConfigTest.SecurityProbeController.class
})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;

    @MockitoBean private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        given(securityProperties.getAllowedOrigins()).willReturn(List.of("http://localhost:5173"));
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
    void 유효한_ACCESS_TOKEN이면_현재사용자_ID를_Controller에서_사용할_수_있다() throws Exception {
        // given
        given(jwtService.parseServiceAuthToken("service-token")).willReturn(42L);

        // when & then
        mockMvc.perform(
                        get("/api/v1/security/protected")
                                .cookie(
                                        new Cookie(
                                                AuthCookieFactory.ACCESS_TOKEN_COOKIE,
                                                "service-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void CORS_설정은_허용된_Origin을_등록한다() {
        SecurityConfig securityConfig =
                new SecurityConfig(
                        jwtService,
                        new ApiAuthenticationEntryPoint(new ObjectMapper()),
                        new ApiAccessDeniedHandler(new ObjectMapper()),
                        securityProperties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/security/protected");

        CorsConfiguration configuration =
                securityConfig.corsConfigurationSource().getCorsConfiguration(request);

        assertEquals(List.of("http://localhost:5173"), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(configuration.getAllowCredentials());
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/v1/security/protected")
        Map<String, Long> protectedEndpoint(
                @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
            return Map.of("userId", principal.userId());
        }

        @GetMapping("/api/v1/auth/public")
        ResponseEntity<Void> publicEndpoint() {
            return ResponseEntity.ok().build();
        }
    }
}
