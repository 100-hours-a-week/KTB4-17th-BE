package com.team.dating_backend.persona.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.persona.dto.response.PersonaProfileResponse;
import com.team.dating_backend.persona.service.PersonaProfileService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/persona")
public class PersonaProfileController {

    private final PersonaProfileService service;

    @GetMapping("/me")
    public SuccessResponse<PersonaProfileResponse> getMyProfile(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        return SuccessResponse.of("persona_retrieved", service.getMyProfile(principal.userId()));
    }
}
