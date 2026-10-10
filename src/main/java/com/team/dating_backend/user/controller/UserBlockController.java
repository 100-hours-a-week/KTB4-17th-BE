package com.team.dating_backend.user.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.dto.response.UserBlockResponse;
import com.team.dating_backend.user.service.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/blocks")
public class UserBlockController {

    private final UserBlockService userBlockService;

    @PutMapping("/{targetUserId}")
    public ResponseEntity<SuccessResponse<UserBlockResponse>> block(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long targetUserId) {
        UserBlockResult result = userBlockService.block(principal.userId(), targetUserId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .body(SuccessResponse.of("user_block_success", result.response()));
    }
}
