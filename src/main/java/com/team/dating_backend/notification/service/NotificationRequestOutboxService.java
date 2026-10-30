package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationRequestOutboxService {

    private final NotificationRequestOutboxRepository repository;
    private final NotificationRequestOutboxCodec codec;

    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationRequestOutbox append(NotificationCreateRequested request) {
        String payload = codec.encode(request);
        var outbox = new NotificationRequestOutbox(request.sourceEventId(), request.recipientUserId(), request.type(),
            payload, LocalDateTime.now(ZoneOffset.UTC));
        return repository.save(outbox);
    }

    @Transactional(readOnly = true)
    public List<NotificationRequestOutbox> findDue(LocalDateTime now, int limit) {
        if (now == null || limit <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return repository.findDue(NotificationOutboxStatus.PENDING, now.truncatedTo(ChronoUnit.MICROS),
            PageRequest.of(0, limit));
    }

    public NotificationCreateRequested restore(NotificationRequestOutbox outbox) {
        if (outbox == null) {
            throw new NotificationInvalidCreateRequestException();
        }
        NotificationCreateRequested request = codec.decode(outbox.getPayload());
        if (!request.sourceEventId().equals(outbox.getSourceEventId())
            || !request.recipientUserId().equals(outbox.getRecipientUserId())
            || request.type() != outbox.getType()) {
            throw new NotificationInvalidCreateRequestException();
        }
        return request;
    }
}
