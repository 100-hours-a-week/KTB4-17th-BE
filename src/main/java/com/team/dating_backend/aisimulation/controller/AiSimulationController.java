package com.team.dating_backend.aisimulation.controller;

import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai-simulations")
public class AiSimulationController {

    private final AiSimulationService simulationService;
    private final AiSimulationQueryService queryService;

    @PostMapping
    public ResponseEntity<SuccessResponse<AiSimulationResponses.Detail>> run(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody AiSimulationCreateRequest request) {
        AiSimulationResponses.Detail response = simulationService.run(
            principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("ai_simulation_completed", response));
    }

    @GetMapping
    public SuccessResponse<List<AiSimulationResponses.Summary>> list(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        return SuccessResponse.of(
            "ai_simulation_list_success",
            queryService.list(principal.userId()));
    }

    @GetMapping("/{simulationId}")
    public SuccessResponse<AiSimulationResponses.Detail> get(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long simulationId) {
        return SuccessResponse.of(
            "ai_simulation_get_success",
            queryService.get(principal.userId(), simulationId));
    }

    @GetMapping("/{simulationId}/report")
    public SuccessResponse<AiSimulationResponses.MatchingReport> getReport(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long simulationId) {
        return SuccessResponse.of(
            "ai_simulation_report_get_success",
            queryService.getReport(principal.userId(), simulationId));
    }
}
