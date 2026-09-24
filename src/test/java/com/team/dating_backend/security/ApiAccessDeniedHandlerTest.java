package com.team.dating_backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

class ApiAccessDeniedHandlerTest {

    @Test
    void 권한_부족을_FORBIDDEN_JSON으로_응답한다() throws Exception {
        // given
        ApiAccessDeniedHandler accessDeniedHandler = new ApiAccessDeniedHandler(new ObjectMapper());
        HttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        accessDeniedHandler.handle(
            request, response, new AccessDeniedException("Access is denied"));

        // then
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"FORBIDDEN\""));
    }
}
