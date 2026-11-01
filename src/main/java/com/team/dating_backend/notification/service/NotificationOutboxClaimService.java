package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationOutboxClaimProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(NotificationOutboxClaimProperties.class)
public class NotificationOutboxClaimService {

    private final NotificationRequestOutboxRepository repository;
    private final NotificationOutboxClaimProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<NotificationRequestOutbox> tryClaim(Long id) {
        requireId(id);
        long durationMicros;
        try {
            if (properties.getDurationMs() <= 0) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            durationMicros = Math.multiplyExact(properties.getDurationMs(), 1000L);
        } catch (ArithmeticException failure) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, failure);
        }
        if (repository.findByIdForUpdate(id).isEmpty()) {
            return Optional.empty();
        }
        if (repository.claim(id, UUID.randomUUID().toString(), durationMicros) != 1) {
            return Optional.empty();
        }
        return Optional.of(repository.findById(id)
            .orElseThrow(() -> new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(Long id, String token) {
        requireOwner(id, token);
        return repository.findByIdForUpdate(id).isPresent() && repository.markPublishedByToken(id, token) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(Long id, String token) {
        requireOwner(id, token);
        return repository.findByIdForUpdate(id).isPresent() && repository.releaseByToken(id, token) == 1;
    }

    private void requireOwner(Long id, String token) {
        requireId(id);
        if (token == null || token.length() != 36 || token.isBlank()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
