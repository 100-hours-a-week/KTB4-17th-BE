package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EmbeddedKafka(partitions = 1, topics = ChatMessageKafkaPublisherTest.TOPIC)
class ChatMessageKafkaPublisherTest {

    static final String TOPIC = "chat.message-created.v1";

    private static final Long MESSAGE_ID = 100L;
    private static final Long CHAT_ROOM_ID = 30L;
    private static final UUID CLIENT_MESSAGE_ID = UUID.fromString(
        "d9dd8900-cf39-4bfd-bd8f-feb628016cb4");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 26, 20, 30);

    @Test
    void Key로_chatRoomId를_사용해_이벤트를_Kafka에_발행한다(
        EmbeddedKafkaBroker embeddedKafka) throws Exception {
        ChatMessageCreatedKafkaEvent event = event();
        ChatMessageKafkaEventFactory eventFactory = org.mockito.Mockito.mock(
            ChatMessageKafkaEventFactory.class);
        given(eventFactory.create(MESSAGE_ID)).willReturn(event);

        Map<String, Object> producerProperties = KafkaTestUtils.producerProps(embeddedKafka);
        DefaultKafkaProducerFactory<Long, ChatMessageCreatedKafkaEvent> producerFactory = new DefaultKafkaProducerFactory<>(
            producerProperties, new LongSerializer(), new JacksonJsonSerializer<>());
        KafkaTemplate<Long, ChatMessageCreatedKafkaEvent> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        try (Consumer<Long, String> consumer = consumer(embeddedKafka)) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            ChatMessageKafkaPublisher publisher = new ChatMessageKafkaPublisher(
                eventFactory, kafkaTemplate, TOPIC);

            publisher.publish(MESSAGE_ID).get(10, TimeUnit.SECONDS);

            ConsumerRecord<Long, String> record = KafkaTestUtils.getSingleRecord(
                consumer, TOPIC, Duration.ofSeconds(10));
            JsonNode payload = new ObjectMapper().readTree(record.value());
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(CHAT_ROOM_ID);
            assertThat(payload.get("eventId").asText())
                .isEqualTo("chat-message-created:100");
            assertThat(payload.get("chatRoomId").asLong()).isEqualTo(CHAT_ROOM_ID);
            assertThat(payload.get("messageId").asLong()).isEqualTo(MESSAGE_ID);
            assertThat(payload.get("clientMessageId").asText())
                .isEqualTo(CLIENT_MESSAGE_ID.toString());
            assertThat(payload.get("senderUserId").asLong()).isEqualTo(1L);
            assertThat(payload.get("receiverUserId").asLong()).isEqualTo(2L);
            assertThat(payload.get("messageType").asText()).isEqualTo("TEXT");
            assertThat(payload.get("textContent").asText()).isEqualTo("안녕하세요");
            assertThat(payload.get("isDelegated").asBoolean()).isFalse();
            assertThat(payload.get("isUnreadByReceiver").asBoolean()).isTrue();
            verify(eventFactory).create(MESSAGE_ID);
        } finally {
            kafkaTemplate.destroy();
        }
    }

    private Consumer<Long, String> consumer(EmbeddedKafkaBroker embeddedKafka) {
        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
            embeddedKafka, "chat-message-publisher-test", false);
        return new KafkaConsumer<>(
            consumerProperties, new LongDeserializer(), new StringDeserializer());
    }

    private ChatMessageCreatedKafkaEvent event() {
        return new ChatMessageCreatedKafkaEvent(
            "chat-message-created:100",
            CREATED_AT,
            CHAT_ROOM_ID,
            MESSAGE_ID,
            CLIENT_MESSAGE_ID,
            1L,
            2L,
            List.of(1L, 2L),
            false,
            ChatMessageType.TEXT,
            "안녕하세요",
            null,
            true,
            CREATED_AT);
    }
}
