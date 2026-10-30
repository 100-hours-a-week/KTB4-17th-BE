package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class NotificationOutboxRelay {

    private final NotificationRequestOutboxService outboxService;
    private final NotificationRequestProducer producer;

    @Transactional(propagation = Propagation.NEVER)
    public boolean publish(Long outboxId) throws ExecutionException, InterruptedException, TimeoutException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        var candidate = outboxService.findDueById(outboxId, LocalDateTime.now(ZoneOffset.UTC));
        if (candidate.isEmpty()) {
            return false;
        }
        var request = outboxService.restore(candidate.get());
        if (producer.publishAndAwait(request) == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        outboxService.markPublished(outboxId, LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }
}
