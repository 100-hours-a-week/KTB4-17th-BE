package com.team.dating_backend.notification.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.event.NotificationCreateRequestedEventListener;
import com.team.dating_backend.notification.kafka.NotificationPublishDispatcher;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationService;
import com.team.dating_backend.notification.service.NotificationLookupService;
import com.team.dating_backend.notification.service.NotificationPersistenceService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import(
    {NotificationRequestService.class, NotificationCreateRequestedValidator.class,
        NotificationCreateRequestedEventListener.class, NotificationCreationService.class,
        NotificationPersistenceService.class, NotificationLookupService.class}
)
public class NotificationRequestTestConfiguration {

    @Bean
    public PublishedRequests publishedRequests(NotificationCreationService creationService) {
        return new PublishedRequests(creationService);
    }

    @Bean
    public NotificationPublishDispatcher notificationPublishDispatcher(PublishedRequests requests) throws Exception {
        var producer = mock(NotificationRequestProducer.class);
        doAnswer(invocation -> {
            requests.record(invocation.getArgument(0));
            return null;
        }).when(producer).publishAndAwait(any());
        return new NotificationPublishDispatcher(Runnable::run, producer, mock(NotificationFailureReporter.class));
    }

    public static class PublishedRequests {

        private final NotificationCreationService creationService;
        private final List<NotificationCreateRequested> published = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<NotificationCreateRequested> pending = new ConcurrentLinkedQueue<>();

        private PublishedRequests(NotificationCreationService creationService) {
            this.creationService = creationService;
        }

        private void record(NotificationCreateRequested request) {
            pending.add(request);
            published.add(request);
        }

        public List<NotificationCreateRequested> snapshot() {
            return List.copyOf(published);
        }

        public void persistPending() {
            NotificationCreateRequested request;
            while ((request = pending.peek()) != null) {
                creationService.createOrLoad(request);
                pending.remove();
            }
        }

        public void clear() {
            pending.clear();
            published.clear();
        }
    }
}
