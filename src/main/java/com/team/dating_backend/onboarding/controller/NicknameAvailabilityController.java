package com.team.dating_backend.onboarding.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.onboarding.dto.request.NicknameAvailabilityRequest;
import com.team.dating_backend.onboarding.dto.response.NicknameAvailabilityResponse;
import com.team.dating_backend.onboarding.service.NicknameAvailabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/nicknames")
public class NicknameAvailabilityController {

    private final NicknameAvailabilityService nicknameAvailabilityService;

    @GetMapping("/availability")
    public SuccessResponse<NicknameAvailabilityResponse> checkNicknameAvailability(
        @Valid @ModelAttribute NicknameAvailabilityRequest request) {
        return SuccessResponse.of(
            "nickname_availability_check_success",
            nicknameAvailabilityService.checkNicknameAvailability(request.nickname()));
    }
}
