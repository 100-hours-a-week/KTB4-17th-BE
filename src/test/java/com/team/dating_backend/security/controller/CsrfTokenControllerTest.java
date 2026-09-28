package com.team.dating_backend.security.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = CsrfTokenController.class)
@Import(
    {
        SecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        CsrfTokenControllerTest.ProtectedPostController.class
    }
)
class CsrfTokenControllerTest {

    private static final String ACCESS_TOKEN = "service-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        given(securityProperties.getAllowedOrigins()).willReturn(List.of("http://localhost:5173"));
        given(jwtService.parseServiceAuthToken(ACCESS_TOKEN)).willReturn(42L);
    }

    @Test
    void 인증되지_않은_사용자는_CSRF_토큰을_발급받을_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void 인증된_사용자에게_CSRF_토큰과_쿠키를_발급한다() throws Exception {
        mockMvc.perform(get("/api/v1/csrf").cookie(accessTokenCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(jsonPath("$.parameterName").value("_csrf"))
            .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void 발급받은_CSRF_토큰과_쿠키로_보호된_POST를_호출할_수_있다() throws Exception {
        MvcResult result = issueCsrfToken();
        String token = responseToken(result);
        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/csrf-test/protected")
            .cookie(accessTokenCookie(), csrfCookie)
            .header("X-XSRF-TOKEN", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void CSRF_헤더가_없으면_보호된_POST를_호출할_수_없다() throws Exception {
        MvcResult result = issueCsrfToken();
        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/csrf-test/protected")
            .cookie(accessTokenCookie(), csrfCookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void CSRF_쿠키와_헤더가_일치하지_않으면_보호된_POST를_호출할_수_없다() throws Exception {
        MvcResult result = issueCsrfToken();
        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/csrf-test/protected")
            .cookie(accessTokenCookie(), csrfCookie)
            .header("X-XSRF-TOKEN", "invalid-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    private MvcResult issueCsrfToken() throws Exception {
        return mockMvc.perform(get("/api/v1/csrf").cookie(accessTokenCookie()))
            .andExpect(status().isOk())
            .andReturn();
    }

    private String responseToken(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("token")
            .asText();
    }

    private Cookie accessTokenCookie() {
        return new Cookie(AuthCookieFactory.ACCESS_TOKEN_COOKIE, ACCESS_TOKEN);
    }

    @RestController
    static class ProtectedPostController {

        @PostMapping("/api/v1/csrf-test/protected")
        Map<String, Long> protectedPost(
            @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
            return Map.of("userId", principal.userId());
        }
    }
}
