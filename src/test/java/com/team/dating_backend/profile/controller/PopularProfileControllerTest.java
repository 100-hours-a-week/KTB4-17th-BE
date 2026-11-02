package com.team.dating_backend.profile.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.profile.dto.response.PopularProfilesResponse;
import com.team.dating_backend.profile.service.PopularProfileGetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = PopularProfileController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class PopularProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private NotificationEmitterRegistry notificationEmitterRegistry;
    @MockitoBean
    private NotificationSseObservation notificationSseObservation;
    @MockitoBean
    private PopularProfileGetService service;

    @Test
    void 공통_인기_프로필_목록을_반환한다() throws Exception {
        given(service.getPopularProfiles()).willReturn(new PopularProfilesResponse(List.of(
            new PopularProfilesResponse.Item(1, 10L, "민지", "서울 강남구", 3L, null))));

        mockMvc.perform(get("/api/v1/popular-profiles"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.message").value("popular_profiles_get_success"))
            .andExpect(jsonPath("$.data.profiles[0].rank").value(1))
            .andExpect(jsonPath("$.data.profiles[0].userId").value(10))
            .andExpect(jsonPath("$.data.profiles[0].nickname").value("민지"))
            .andExpect(jsonPath("$.data.profiles[0].likeCount").value(3));
    }
}
