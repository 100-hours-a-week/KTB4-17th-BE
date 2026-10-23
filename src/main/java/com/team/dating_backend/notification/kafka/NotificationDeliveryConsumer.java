package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryConsumer {

    private final NotificationDeliveryEventValidator validator;
    private final NotificationSseSender sender;

    @KafkaListener(
        id = "notificationDeliveryConsumer",
        idIsGroup = false,
        groupId = "#{notificationSseProcessIdentity.groupId}",
        topics = "${app.notification.kafka.delivery-topic}",
        containerFactory = "notificationDeliveryKafkaListenerContainerFactory",
        autoStartup = "${app.notification.delivery.enabled:false}"
    )
    public void consume(ConsumerRecord<String, NotificationDeliveryEvent> record) {
        NotificationDeliveryEvent event = record.value();
        validator.validate(event);
        sender.sendNotification(event.recipientUserId(), event.notification());
    }
}
