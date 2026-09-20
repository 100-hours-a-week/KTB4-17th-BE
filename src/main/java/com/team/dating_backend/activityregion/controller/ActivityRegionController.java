package com.team.dating_backend.activityregion.controller;

import com.team.dating_backend.activityregion.dto.request.ActivityRegionSearchRequest;
import com.team.dating_backend.activityregion.dto.response.ActivityRegionSearchItem;
import com.team.dating_backend.activityregion.dto.response.ActivityRegionSearchResponse;
import com.team.dating_backend.activityregion.service.ActivityRegionSearchService;
import com.team.dating_backend.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activity-regions")
public class ActivityRegionController {

    private final ActivityRegionSearchService activityRegionSearchService;

    @GetMapping
    public ApiResponse<ActivityRegionSearchResponse> searchRegions(
            @Valid @ModelAttribute ActivityRegionSearchRequest request) {
        List<ActivityRegionSearchItem> items =
                activityRegionSearchService.searchRegions(request.normalizedQuery()).stream()
                        .map(ActivityRegionSearchItem::from)
                        .toList();

        return ApiResponse.success(
                "activity_regions_get_success", new ActivityRegionSearchResponse(items));
    }
}
