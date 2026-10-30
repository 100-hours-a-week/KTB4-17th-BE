package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class NotificationRequestOutboxCodec {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final NotificationCreateRequestedValidator validator;

    public String encode(NotificationCreateRequested request) {
        validator.validate(request);
        return JSON.writeValueAsString(request);
    }

    public NotificationCreateRequested decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new NotificationInvalidCreateRequestException();
        }
        NotificationCreateRequested request;
        try {
            request = JSON.readValue(payload, NotificationCreateRequested.class);
        } catch (JacksonException exception) {
            var invalidRequest = new NotificationInvalidCreateRequestException();
            invalidRequest.initCause(exception);
            throw invalidRequest;
        }
        validator.validate(request);
        return request;
    }
}
