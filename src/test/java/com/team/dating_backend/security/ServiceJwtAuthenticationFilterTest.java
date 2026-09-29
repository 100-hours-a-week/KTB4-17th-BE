package com.team.dating_backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.TestJwtSecret;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ServiceJwtAuthenticationFilterTest {

    private JwtService jwtService;
    private ServiceJwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TestJwtSecret.generate());
        jwtProperties.setPendingExpirationMinutes(10);
        jwtProperties.setServiceExpirationMinutes(60);

        jwtService = new JwtService(jwtProperties);
        filter = new ServiceJwtAuthenticationFilter(jwtService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 유효한_Bearer_토큰이면_SecurityContext에_인증정보를_저장한다() throws Exception {
        // given
        String accessToken = jwtService.createServiceAuthToken(123L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.isAuthenticated());

        ServiceAuthenticationPrincipal principal = assertInstanceOf(
            ServiceAuthenticationPrincipal.class, authentication.getPrincipal());

        assertEquals(123L, principal.userId());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void Bearer_토큰이_없으면_인증정보를_저장하지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void 쿠키에만_ACCESS_TOKEN이_있으면_인증정보를_저장하지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("ACCESS_TOKEN", jwtService.createServiceAuthToken(123L)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void 잘못된_Bearer_토큰은_인증정보를_저장하지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void Pending_토큰은_Bearer_ACCESS_TOKEN으로_인증되지_않는다() throws Exception {
        String pendingToken = jwtService.createPendingRegistrationToken(AuthProvider.KAKAO,
            "kakao-123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + pendingToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }
}
