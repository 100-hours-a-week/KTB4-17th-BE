package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class OAuthStateServiceTest {

    private final OAuthStateService oauthStateService = new OAuthStateService();

    @Test
    void state는_한_번_검증되면_즉시_소비된다() {
        // given
        MockHttpSession session = new MockHttpSession();
        String state = oauthStateService.createState(AuthProvider.KAKAO, session);

        // when & then
        assertDoesNotThrow(
                () ->
                        oauthStateService.validateAndConsumeState(
                                AuthProvider.KAKAO, state, session));
        assertThrows(
                OAuthInvalidRequestException.class,
                () ->
                        oauthStateService.validateAndConsumeState(
                                AuthProvider.KAKAO, state, session));
    }

    @Test
    void 일치하지_않는_state가_들어오면_저장된_state도_소비된다() {
        // given
        MockHttpSession session = new MockHttpSession();
        String state = oauthStateService.createState(AuthProvider.KAKAO, session);

        // when & then
        assertThrows(
                OAuthInvalidRequestException.class,
                () ->
                        oauthStateService.validateAndConsumeState(
                                AuthProvider.KAKAO, "wrong-state", session));
        assertThrows(
                OAuthInvalidRequestException.class,
                () ->
                        oauthStateService.validateAndConsumeState(
                                AuthProvider.KAKAO, state, session));
    }
}
