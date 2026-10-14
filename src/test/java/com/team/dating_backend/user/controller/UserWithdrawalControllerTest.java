package com.team.dating_backend.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.user.service.UserWithdrawalService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

class UserWithdrawalControllerTest {

    @Test
    void 탈퇴가_성공하면_204와_캐시방지_헤더를_반환하고_인증_관련_쿠키_3개를_만료한다() {
        UserWithdrawalService service = mock(UserWithdrawalService.class);
        AuthCookieFactory cookieFactory = mock(AuthCookieFactory.class);
        when(cookieFactory.deleteAccessToken()).thenReturn(deletedCookie("ACCESS_TOKEN"));
        when(cookieFactory.deleteRefreshToken()).thenReturn(deletedCookie("REFRESH_TOKEN"));
        when(cookieFactory.deletePendingRegistrationToken())
            .thenReturn(deletedCookie("PENDING_REGISTRATION_TOKEN"));
        UserWithdrawalController controller = new UserWithdrawalController(service, cookieFactory);

        ResponseEntity<Void> response = controller.withdraw(
            new ServiceAuthenticationPrincipal(7L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(3)
            .allMatch(cookie -> cookie.contains("Max-Age=0"));
        assertThat(cookies)
            .anyMatch(cookie -> cookie.startsWith("ACCESS_TOKEN="))
            .anyMatch(cookie -> cookie.startsWith("REFRESH_TOKEN="))
            .anyMatch(cookie -> cookie.startsWith("PENDING_REGISTRATION_TOKEN="));
        verify(service).withdraw(7L);
    }

    private ResponseCookie deletedCookie(String name) {
        return ResponseCookie.from(name, "").maxAge(0).path("/").build();
    }
}
