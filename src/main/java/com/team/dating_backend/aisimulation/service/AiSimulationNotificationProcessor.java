package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationNotificationProcessor {

    private static final String OUTBOX_UNIQUE_CONSTRAINT = "uk_notification_outbox_source_recipient_type";

    private final AiSimulationNotificationService notifications;

    @Transactional(propagation = Propagation.NEVER)
    public AiSimulationNotificationResult requested(Long sessionId) {
        return recordNotification(() -> notifications.requested(sessionId));
    }

    @Transactional(propagation = Propagation.NEVER)
    public AiSimulationNotificationResult completed(Long sessionId, AiSimulationNotificationRecipient recipient) {
        return recordNotification(() -> notifications.completed(sessionId, recipient));
    }

    private AiSimulationNotificationResult recordNotification(Supplier<AiSimulationNotificationResult> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException exception) {
            if (!isOutboxDuplicate(exception)) {
                throw exception;
            }
            return AiSimulationNotificationResult.ALREADY_RECORDED;
        }
    }

    private boolean isOutboxDuplicate(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (OUTBOX_UNIQUE_CONSTRAINT.equals(name)
                    || name != null && name.endsWith("." + OUTBOX_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
