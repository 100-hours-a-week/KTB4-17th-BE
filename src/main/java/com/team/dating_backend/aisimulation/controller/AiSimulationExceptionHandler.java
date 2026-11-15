package com.team.dating_backend.aisimulation.controller;

import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.exception.AiSimulationRequestPublishUnconfirmedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AiSimulationController.class)
public class AiSimulationExceptionHandler {

    @ExceptionHandler(AiSimulationRequestPublishUnconfirmedException.class)
    public ResponseEntity<AiSimulationResponses.RequestPublishFailure> handleRequestPublishUnconfirmed(
        AiSimulationRequestPublishUnconfirmedException exception) {
        var errorCode = exception.getErrorCode();
        log.error("Simulation request publish unconfirmed. errorCode={}, simulationId={}, causeType={}",
            errorCode.name(), exception.getSimulationId(),
            exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
        return ResponseEntity.status(errorCode.status()).body(new AiSimulationResponses.RequestPublishFailure(
            errorCode.name(), exception.getSimulationId()));
    }
}
