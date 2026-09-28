package com.team.dating_backend.security;

import com.team.dating_backend.auth.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
@Slf4j
public class ServiceJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String accessToken = BearerTokenResolver.resolve(
            request.getHeader(HttpHeaders.AUTHORIZATION));

        if (accessToken != null) {
            authenticate(accessToken, request);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String accessToken, HttpServletRequest request) {
        try {
            Long userId = jwtService.parseServiceAuthToken(accessToken);

            ServiceAuthenticationPrincipal principal = new ServiceAuthenticationPrincipal(userId);

            Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null,
                List.of());

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            log.info("Service JWT authentication succeeded. method={}, path={}, userId={}",
                request.getMethod(), request.getRequestURI(), userId);
        } catch (JwtException exception) {
            SecurityContextHolder.clearContext();
            log.warn("Service JWT authentication failed. method={}, path={}, reason={}",
                request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName());
        }
    }
}
