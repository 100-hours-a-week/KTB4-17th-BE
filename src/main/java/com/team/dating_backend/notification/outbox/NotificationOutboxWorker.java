package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationOutboxWorker {

    private final NotificationRequestOutboxService outboxService;
    private final NotificationCreationProcessor processor;
    private final NotificationOutboxClaimService claimService;

    @Transactional(propagation = Propagation.NEVER)
    public void process(NotificationRequestOutbox outbox)
        throws ExecutionException, InterruptedException, TimeoutException {
        if (outbox == null || outbox.getId() == null || outbox.getId() <= 0
            || outbox.getStatus() != NotificationOutboxStatus.PENDING
            || outbox.getProcessingToken() == null || outbox.getProcessingToken().length() != 36
            || outbox.getProcessingToken().isBlank() || outbox.getProcessingUntil() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            requireNotInterrupted();
            var request = outboxService.restore(outbox);
            requireNotInterrupted();
            processor.process(request);
            requireNotInterrupted();
            if (!claimService.markCompleted(outbox.getId(), outbox.getProcessingToken())) {
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }
}
