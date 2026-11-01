package com.team.dating_backend.notification.support;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationService;
import com.team.dating_backend.notification.service.NotificationLookupService;
import com.team.dating_backend.notification.service.NotificationPersistenceService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

@TestConfiguration(proxyBeanMethods = false)
@Import(
    {NotificationRequestService.class, NotificationRequestOutboxService.class,
        NotificationRequestOutboxCodec.class, NotificationCreateRequestedValidator.class,
        NotificationCreationService.class, NotificationPersistenceService.class, NotificationLookupService.class}
)
public class NotificationRequestTestConfiguration {

    @Bean
    public StoredRequests storedRequests(NotificationRequestOutboxRepository repository,
        NotificationRequestOutboxService outboxService, NotificationCreationService creationService) {
        return new StoredRequests(repository, outboxService, creationService);
    }

    public static class StoredRequests {

        private final NotificationRequestOutboxRepository repository;
        private final NotificationRequestOutboxService outboxService;
        private final NotificationCreationService creationService;
        private final Set<Long> createdNotificationOutboxIds = ConcurrentHashMap.newKeySet();

        private StoredRequests(NotificationRequestOutboxRepository repository,
            NotificationRequestOutboxService outboxService, NotificationCreationService creationService) {
            this.repository = repository;
            this.outboxService = outboxService;
            this.creationService = creationService;
        }

        public List<NotificationCreateRequested> snapshot() {
            return repository.findAll(Sort.by("id")).stream().map(outboxService::restore).toList();
        }

        public void createNotificationsFromStoredRequests() {
            for (var outbox : repository.findAll(Sort.by("id"))) {
                if (!createdNotificationOutboxIds.contains(outbox.getId())) {
                    creationService.createOrLoad(outboxService.restore(outbox));
                    createdNotificationOutboxIds.add(outbox.getId());
                }
            }
        }

        public void clear() {
            repository.deleteAllInBatch();
            createdNotificationOutboxIds.clear();
        }
    }
}
