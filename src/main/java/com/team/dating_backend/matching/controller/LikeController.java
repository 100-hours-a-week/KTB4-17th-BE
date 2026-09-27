package com.team.dating_backend.matching.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.matching.dto.request.LikeCreateRequest;
import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.dto.response.SentLikesGetResponse;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.matching.service.SentLikeGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/likes")
public class LikeController {

    private final LikeSendService likeSendService;
    private final SentLikeGetService sentLikeGetService;

    @PostMapping
    public ResponseEntity<SuccessResponse<LikeCreateResponse>> sendLike(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody LikeCreateRequest request) {
        LikeCreateResponse response = likeSendService.sendLike(principal.userId(),
            request.receiverId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("like_create_success", response));
    }

    @GetMapping("/sent")
    public ResponseEntity<SuccessResponse<SentLikesGetResponse>> getSentLikes(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestParam(required = false) Long cursor) {
        SentLikesGetResponse response = sentLikeGetService.getSentLikes(
            principal.userId(), cursor);
        return ResponseEntity.ok(SuccessResponse.of("sent_like_get_success", response));
    }
}
