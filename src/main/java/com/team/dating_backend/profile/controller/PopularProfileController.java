package com.team.dating_backend.profile.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profile.dto.response.PopularProfilesResponse;
import com.team.dating_backend.profile.service.PopularProfileGetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/popular-profiles")
public class PopularProfileController {

    private final PopularProfileGetService popularProfileGetService;

    @GetMapping
    public ResponseEntity<SuccessResponse<PopularProfilesResponse>> getPopularProfiles() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(SuccessResponse.of("popular_profiles_get_success", popularProfileGetService.getPopularProfiles()));
    }
}
