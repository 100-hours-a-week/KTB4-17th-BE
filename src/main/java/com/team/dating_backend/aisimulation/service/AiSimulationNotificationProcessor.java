package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
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
        try {
            return notifications.requested(sessionId);
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
