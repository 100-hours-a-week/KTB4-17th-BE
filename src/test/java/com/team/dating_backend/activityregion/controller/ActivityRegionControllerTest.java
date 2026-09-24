package com.team.dating_backend.activityregion.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.service.ActivityRegionSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityRegionController.class)
class ActivityRegionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityRegionSearchService activityRegionSearchService;

    @Test
    void 활동_지역을_검색하면_검색_결과를_반환한다() throws Exception {
        ActivityRegion seoulJung = activityRegion(101L, "11020", "서울특별시", "중구");
        ActivityRegion busanJung = activityRegion(102L, "21010", "부산광역시", "중구");
        given(activityRegionSearchService.searchRegions("중구"))
            .willReturn(List.of(seoulJung, busanJung));

        mockMvc.perform(get("/api/v1/activity-regions").param("query", "중구"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("activity_regions_get_success"))
            .andExpect(jsonPath("$.data.items[0].activityRegionId").value(101))
            .andExpect(jsonPath("$.data.items[0].regionCode").value("11020"))
            .andExpect(jsonPath("$.data.items[0].provinceName").value("서울특별시"))
            .andExpect(jsonPath("$.data.items[0].regionName").value("중구"))
            .andExpect(jsonPath("$.data.items[0].representativeLatitude").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].representativeLongitude").doesNotExist())
            .andExpect(jsonPath("$.data.items[1].activityRegionId").value(102))
            .andExpect(jsonPath("$.data.items[1].regionCode").value("21010"))
            .andExpect(jsonPath("$.data.items[1].provinceName").value("부산광역시"))
            .andExpect(jsonPath("$.data.items[1].regionName").value("중구"));

        verify(activityRegionSearchService).searchRegions("중구");
    }

    @Test
    void 검색_결과가_없으면_빈_목록을_반환한다() throws Exception {
        given(activityRegionSearchService.searchRegions("존재하지않는지역")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-regions").param("query", "존재하지않는지역"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("activity_regions_get_success"))
            .andExpect(jsonPath("$.data.items").isEmpty());

        verify(activityRegionSearchService).searchRegions("존재하지않는지역");
    }

    @Test
    void 검색어의_앞뒤_공백을_제거해_조회한다() throws Exception {
        given(activityRegionSearchService.searchRegions("처인구")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-regions").param("query", " 처인구 "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());

        verify(activityRegionSearchService).searchRegions("처인구");
    }

    private ActivityRegion activityRegion(
        Long id, String regionCode, String provinceName, String regionName) {
        ActivityRegion activityRegion = mock(ActivityRegion.class);
        given(activityRegion.getId()).willReturn(id);
        given(activityRegion.getRegionCode()).willReturn(regionCode);
        given(activityRegion.getProvinceName()).willReturn(provinceName);
        given(activityRegion.getRegionName()).willReturn(regionName);
        return activityRegion;
    }
}
