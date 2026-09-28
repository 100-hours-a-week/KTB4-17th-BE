package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class OAuthLoginCodeServiceTest {

    private final OAuthLoginCodeService service = new OAuthLoginCodeService();

    @Test
    void 로그인_코드는_한_번만_교환할_수_있고_발급_세션에_묶인다() {
        MockHttpSession issuingSession = new MockHttpSession();
        MockHttpSession otherSession = new MockHttpSession();

        String code = service.issue("service-token", "refresh-token", issuingSession);

        assertEquals(43, code.length());
        assertTrue(service.consume(code, otherSession).isEmpty());
        assertEquals(
            Optional.of(new IssuedAuthTokens("service-token", "refresh-token")),
            service.consume(code, issuingSession));
        assertFalse(service.consume(code, issuingSession).isPresent());
    }
}
