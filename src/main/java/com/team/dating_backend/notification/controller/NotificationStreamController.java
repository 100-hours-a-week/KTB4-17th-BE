package com.team.dating_backend.notification.controller;

import com.team.dating_backend.notification.service.NotificationSubscriptionService;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationStreamController {

    public static final String CONNECTION_ATTRIBUTE = NotificationStreamController.class.getName() + ".connection";

    private final NotificationSubscriptionService subscriptionService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        HttpServletRequest request) throws IOException {
        NotificationSseConnection connection = subscriptionService.subscribe(principal.userId());
        request.setAttribute(CONNECTION_ATTRIBUTE, connection);

        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
            .body(connection.getEmitter());
    }
}
