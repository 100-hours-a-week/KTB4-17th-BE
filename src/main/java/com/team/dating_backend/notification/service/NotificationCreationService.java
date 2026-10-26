package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationCreationService {

    private static final String SOURCE_RECIPIENT_TYPE_UNIQUE_CONSTRAINT = "uk_notifications_source_recipient_type";

    private final NotificationPersistenceService persistenceService;
    private final NotificationLookupService lookupService;

    @Transactional(propagation = Propagation.NEVER)
    public NotificationCreationResult createOrLoad(NotificationCreateRequested request) {
        try {
            return persistenceService.create(request);
        } catch (DataIntegrityViolationException exception) {
            if (!isSourceRecipientTypeUniqueViolation(exception)) {
                throw exception;
            }
            return lookupService.findExisting(request)
                .orElseThrow(() -> exception);
        }
    }

    private boolean isSourceRecipientTypeUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                if (SOURCE_RECIPIENT_TYPE_UNIQUE_CONSTRAINT.equals(constraintName)
                    || (constraintName != null
                        && constraintName.endsWith("." + SOURCE_RECIPIENT_TYPE_UNIQUE_CONSTRAINT))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
