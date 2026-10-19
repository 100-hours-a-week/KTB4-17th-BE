package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.monitoring.ChatOutboxMetrics;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedBatch;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;

class ChatMessageOutboxPublishJobTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long SENDER_PARTICIPANT_ID = 10L;
    private static final Long RECEIVER_PARTICIPANT_ID = 20L;
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final String MESSAGE_DESTINATION = "/queue/chat-messages";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 26, 14, 30);
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long RENEWAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private ChatMessageOutboxClaimService claims;
    private ChatMessageRepository chatMessageRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private RabbitUserEventPublisher eventPublisher;
    private ChatMessageOutboxPublishJob job;
    private SimpleMeterRegistry registry;
    private AtomicLong ticker;
    private ChatRoom room;
    private ChatMessage message;
    private ChatParticipant sender;
    private ChatParticipant receiver;

    @BeforeEach
    void setUp() {
        claims = mock(ChatMessageOutboxClaimService.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        eventPublisher = mock(RabbitUserEventPublisher.class);
        registry = new SimpleMeterRegistry();
        ticker = new AtomicLong();
        job = new ChatMessageOutboxPublishJob(claims, chatMessageRepository,
            chatParticipantRepository, eventPublisher, new ChatOutboxMetrics(registry),
            new ChatOutboxStartGate("", "test", registry));
        ReflectionTestUtils.setField(job, "nanoTime", (LongSupplier) ticker::get);

        given(claims.leaseNanos()).willReturn(LEASE_NANOS);
        given(claims.renewalNanos()).willReturn(RENEWAL_NANOS);
        given(claims.complete(anyLong(), eq(TOKEN))).willReturn(true);
        given(claims.retry(anyLong(), eq(TOKEN), anyLong(), anyString())).willReturn(true);
        given(claims.fail(anyLong(), eq(TOKEN), anyString())).willReturn(true);
        given(claims.renew(eq(TOKEN), anyCollection()))
            .willAnswer(invocation -> invocation.<Collection<Long>>getArgument(1).size());
        given(claims.claimBatch(100)).willReturn(batch(1));

        room = mock(ChatRoom.class);
        given(room.getId()).willReturn(CHAT_ROOM_ID);
        sender = participant(SENDER_PARTICIPANT_ID, 1L, ChatParticipantStatus.ACTIVE);
        receiver = participant(RECEIVER_PARTICIPANT_ID, 2L, ChatParticipantStatus.ACTIVE);
        message = new ChatMessage(room, sender, UUID.randomUUID(), "안녕하세요", CREATED_AT);
        ReflectionTestUtils.setField(message, "id", 100L);
        given(chatMessageRepository.findByIdWithImageFile(100L)).willReturn(Optional.of(message));
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(sender, receiver));
    }

    @Test
    void 전환_동기화가_끝나기_전에는_선점하거나_발행하지_않는다(@TempDir Path directory) throws Exception {
        Path release = directory.resolve("publish.ready");
        ChatOutboxStartGate gate = new ChatOutboxStartGate(
            release.toString(), "green-new", new SimpleMeterRegistry());
        ReflectionTestUtils.setField(job, "startGate", gate);

        job.publishDueMessages();
        Files.writeString(release, "green-old\n");
        job.publishDueMessages();
        verifyNoInteractions(claims, chatMessageRepository, chatParticipantRepository, eventPublisher);

        Files.writeString(release, "green-new\n");
        job.publishDueMessages();

        verify(claims).claimBatch(100);
        verify(eventPublisher, times(2)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(claims).complete(1L, TOKEN);
    }

    @Test
    void 정상_발행하면_두_활성_참여자에게_개별_이벤트를_보내고_현재_토큰으로_완료한다() {
        ArgumentCaptor<String> userIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);

        job.publishDueMessages();

        verify(claims).claimBatch(100);
        verify(claims).complete(1L, TOKEN);
        verify(eventPublisher, times(2)).publish(userIds.capture(), eq(MESSAGE_DESTINATION), events.capture());
        assertThat(userIds.getAllValues()).containsExactly("1", "2");
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event).isInstanceOf(ChatMessageCreatedEvent.class);
            ChatMessageCreatedEvent created = (ChatMessageCreatedEvent) event;
            assertThat(created.messageId()).isEqualTo(message.getId());
            assertThat(created.clientMessageId()).isEqualTo(message.getClientMessageId());
        });
        assertThat(events.getAllValues()).extracting(event -> ((ChatMessageCreatedEvent) event).mine())
            .containsExactly(true, false);
        verifyNoFailureMutation();
        verify(claims, never()).renew(anyString(), anyCollection());
    }

    @Test
    void 백건을_한번에_선점하고_각_행을_발행한_뒤_개별_완료한다() {
        given(claims.claimBatch(100)).willReturn(batch(100));
        ArgumentCaptor<Long> completedIds = ArgumentCaptor.forClass(Long.class);

        job.publishDueMessages();

        verify(claims, times(1)).claimBatch(100);
        verify(eventPublisher, times(200)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(claims, times(100)).complete(completedIds.capture(), eq(TOKEN));
        assertThat(completedIds.getAllValues()).containsExactlyElementsOf(LongStream.rangeClosed(1, 100)
            .boxed().toList());
        assertThat(resultCount("completed")).isEqualTo(100);
        verify(claims, never()).renew(anyString(), anyCollection());
    }

    @Test
    void 빈_배치는_발행하지_않고_조회_성공만_기록한다() {
        given(claims.claimBatch(100)).willReturn(new ClaimedBatch(null, List.of()));

        job.publishDueMessages();

        verify(claims).claimBatch(100);
        verifyNoInteractions(chatMessageRepository, chatParticipantRepository, eventPublisher);
        verify(claims, never()).complete(anyLong(), anyString());
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isZero();
        assertThat(lastPollSuccess()).isPositive();
    }

    @Test
    void 원본_메시지가_없으면_현재_토큰으로_실패_격리한다() {
        given(chatMessageRepository.findByIdWithImageFile(100L)).willReturn(Optional.empty());

        job.publishDueMessages();

        verify(claims).fail(1L, TOKEN, ChatOutboxFailureCode.MESSAGE_NOT_FOUND.name());
        verify(claims, never()).retry(anyLong(), anyString(), anyLong(), anyString());
        verify(claims, never()).complete(anyLong(), anyString());
        verifyNoInteractions(chatParticipantRepository, eventPublisher);
        assertThat(resultCount("failed")).isEqualTo(1);
    }

    @Test
    void 원본이_없어도_실패_기록의_소유권을_잃으면_지표를_올리지_않고_배치를_멈춘다() {
        given(claims.claimBatch(100)).willReturn(batch(2));
        given(chatMessageRepository.findByIdWithImageFile(100L)).willReturn(Optional.empty());
        given(claims.fail(1L, TOKEN, ChatOutboxFailureCode.MESSAGE_NOT_FOUND.name())).willReturn(false);

        job.publishDueMessages();

        verify(chatMessageRepository, times(1)).findByIdWithImageFile(100L);
        verify(claims, never()).fail(eq(2L), anyString(), anyString());
        assertThat(resultCount("failed")).isZero();
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 참여자_수가_유효하지_않으면_발행하지_않고_실패_격리한다() {
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID)).willReturn(List.of(sender));

        job.publishDueMessages();

        verify(claims).fail(1L, TOKEN, ChatOutboxFailureCode.PARTICIPANTS_INVALID.name());
        verifyNoInteractions(eventPublisher);
        assertThat(resultCount("failed")).isEqualTo(1);
    }

    @Test
    void 발신자가_참여자가_아니면_발행하지_않고_실패_격리한다() {
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID)).willReturn(List.of(
            participant(11L, 3L, ChatParticipantStatus.ACTIVE), receiver));

        job.publishDueMessages();

        verify(claims).fail(1L, TOKEN, ChatOutboxFailureCode.PARTICIPANTS_INVALID.name());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 비활성_참여자는_건너뛰고_활성_참여자_발행_후_완료한다() {
        ReflectionTestUtils.setField(receiver, "status", ChatParticipantStatus.LEFT);

        job.publishDueMessages();

        verify(eventPublisher).publish(eq("1"), eq(MESSAGE_DESTINATION), any());
        verify(eventPublisher, never()).publish(eq("2"), anyString(), any());
        verify(claims).complete(1L, TOKEN);
    }

    @Test
    void 브로커_실패는_현재_토큰으로_재예약하고_완료하지_않는다() {
        doThrow(new IllegalStateException("broker unavailable")).when(eventPublisher)
            .publish(anyString(), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        verify(claims).retry(1L, TOKEN, 1L, ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(claims, never()).fail(anyLong(), anyString(), anyString());
        verify(claims, never()).complete(anyLong(), anyString());
        assertThat(resultCount("retry")).isEqualTo(1);
        assertThat(resultCount("completed")).isZero();
    }

    @Test
    void 누적_실패가_설정된_한도에_도달하면_재예약하지_않고_격리한다() {
        ReflectionTestUtils.setField(job, "maxFailures", 3);
        given(claims.claimBatch(100)).willReturn(new ClaimedBatch(TOKEN,
            List.of(new ClaimedOutbox(1L, 100L, CREATED_AT, 2))));
        doThrow(new IllegalStateException("broker unavailable")).when(eventPublisher)
            .publish(anyString(), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        verify(claims).fail(1L, TOKEN, ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(claims, never()).retry(anyLong(), anyString(), anyLong(), anyString());
        verify(claims, never()).complete(anyLong(), anyString());
        assertThat(resultCount("failed")).isEqualTo(1);
    }

    @Test
    void 브로커_실패_후_재예약_소유권을_잃으면_재시도_지표와_다음_발행을_막는다() {
        given(claims.claimBatch(100)).willReturn(batch(2));
        given(claims.retry(eq(1L), eq(TOKEN), anyLong(), anyString())).willReturn(false);
        doThrow(new IllegalStateException("broker unavailable")).when(eventPublisher)
            .publish(anyString(), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        verify(eventPublisher, times(1)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(chatMessageRepository, times(1)).findByIdWithImageFile(100L);
        assertThat(resultCount("retry")).isZero();
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void DB_선점_장애는_메시지_실패_대신_작업_재시도에_백오프를_적용한다() {
        assertDatabaseClaimBackoff(new DataAccessResourceFailureException("test claim failure"));
    }

    @Test
    void 선점_트랜잭션_COMMIT_실패도_메시지_실패_대신_DB_백오프로_처리한다() {
        assertDatabaseClaimBackoff(new TransactionSystemException("test claim commit failure"));
    }

    @Test
    void 발행_후_DB_완료_실패는_브로커_재시도나_격리로_바꾸지_않는다() {
        given(claims.claimBatch(100)).willReturn(batch(2));
        given(claims.complete(1L, TOKEN))
            .willThrow(new DataAccessResourceFailureException("test completion failure"));

        job.publishDueMessages();

        verify(eventPublisher, times(2)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(claims).complete(1L, TOKEN);
        verify(claims, never()).complete(2L, TOKEN);
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isZero();
        assertThat(resultCount("retry")).isZero();
        assertThat(resultCount("failed")).isZero();
        assertThat(databaseErrors()).isEqualTo(1);
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 완료_시_소유권을_잃으면_다음_행을_발행하지_않는다() {
        given(claims.claimBatch(100)).willReturn(batch(2));
        given(claims.complete(1L, TOKEN)).willReturn(false);

        job.publishDueMessages();

        verify(eventPublisher, times(2)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(chatMessageRepository, times(1)).findByIdWithImageFile(100L);
        verify(claims, never()).complete(2L, TOKEN);
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isZero();
        assertThat(databaseErrors()).isZero();
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 예약_갱신은_완료된_행을_제외한_남은_ID만_대상으로_한다() {
        given(claims.claimBatch(100)).willReturn(batch(3));
        given(claims.complete(1L, TOKEN)).willAnswer(invocation -> {
            ticker.set(RENEWAL_NANOS);
            return true;
        });
        given(claims.complete(2L, TOKEN)).willAnswer(invocation -> {
            ticker.set(2 * RENEWAL_NANOS);
            return true;
        });

        job.publishDueMessages();

        verify(claims).renew(TOKEN, List.of(2L, 3L));
        verify(claims).renew(TOKEN, List.of(3L));
        verify(claims, times(2)).renew(eq(TOKEN), anyCollection());
        verify(eventPublisher, times(6)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        assertThat(resultCount("completed")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void 남은_예약이_전부_갱신되지_않으면_추가_발행을_중단한다(int renewed) {
        given(claims.claimBatch(100)).willReturn(batch(3));
        given(claims.complete(1L, TOKEN)).willAnswer(invocation -> {
            ticker.set(RENEWAL_NANOS);
            return true;
        });
        given(claims.renew(TOKEN, List.of(2L, 3L))).willReturn(renewed);

        job.publishDueMessages();

        verify(claims).renew(TOKEN, List.of(2L, 3L));
        verify(eventPublisher, times(2)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(chatMessageRepository, times(1)).findByIdWithImageFile(100L);
        verify(claims, never()).complete(2L, TOKEN);
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isEqualTo(1);
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 선점_호출이_예약_기간만큼_느리면_행을_읽거나_발행하지_않는다() {
        given(claims.claimBatch(100)).willAnswer(invocation -> {
            ticker.set(LEASE_NANOS);
            return batch(2);
        });

        job.publishDueMessages();

        verifyNoInteractions(chatMessageRepository, chatParticipantRepository, eventPublisher);
        verify(claims, never()).renew(anyString(), anyCollection());
        verify(claims, never()).complete(anyLong(), anyString());
        verifyNoFailureMutation();
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 메시지를_읽는_동안_로컬_예약이_만료되면_첫_발행도_하지_않는다() {
        given(chatMessageRepository.findByIdWithImageFile(100L)).willAnswer(invocation -> {
            ticker.set(LEASE_NANOS);
            return Optional.of(message);
        });

        job.publishDueMessages();

        verifyNoInteractions(eventPublisher);
        verify(claims, never()).complete(anyLong(), anyString());
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isZero();
    }

    @Test
    void 첫_수신자_발행_중_예약이_만료되면_두번째_수신자_발행을_중단한다() {
        doAnswer(invocation -> {
            ticker.set(LEASE_NANOS);
            return null;
        }).when(eventPublisher).publish(eq("1"), eq(MESSAGE_DESTINATION), any());

        job.publishDueMessages();

        verify(eventPublisher).publish(eq("1"), eq(MESSAGE_DESTINATION), any());
        verify(eventPublisher, never()).publish(eq("2"), anyString(), any());
        verify(claims, never()).complete(anyLong(), anyString());
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isZero();
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 예약_갱신_중_기존_예약이_만료되면_갱신_행수가_맞아도_추가_발행을_중단한다() {
        given(claims.claimBatch(100)).willReturn(batch(2));
        given(claims.complete(1L, TOKEN)).willAnswer(invocation -> {
            ticker.set(RENEWAL_NANOS);
            return true;
        });
        given(claims.renew(TOKEN, List.of(2L))).willAnswer(invocation -> {
            ticker.set(TimeUnit.SECONDS.toNanos(31));
            return 1;
        });

        job.publishDueMessages();

        verify(claims).renew(TOKEN, List.of(2L));
        verify(eventPublisher, times(2)).publish(anyString(), eq(MESSAGE_DESTINATION), any());
        verify(claims, never()).complete(2L, TOKEN);
        verifyNoFailureMutation();
        assertThat(resultCount("completed")).isEqualTo(1);
        assertThat(lastPollSuccess()).isZero();
    }

    @Test
    void 발행_완료는_메시지별로_한번_집계하고_클라이언트_수신으로_집계하지_않는다() {
        job.publishDueMessages();

        assertThat(resultCount("completed")).isEqualTo(1);
        assertThat(registry.get("chat.outbox.publish.delay").timer().count()).isEqualTo(1);
        assertThat(lastPollSuccess()).isPositive();
        assertThat(registry.find("chat.messages.received").counter()).isNull();
    }

    private ClaimedBatch batch(int count) {
        return new ClaimedBatch(TOKEN, LongStream.rangeClosed(1, count)
            .mapToObj(id -> new ClaimedOutbox(id, 100L, CREATED_AT, 0)).toList());
    }

    private void assertDatabaseClaimBackoff(RuntimeException failure) {
        given(claims.claimBatch(100)).willThrow(failure);

        job.publishDueMessages();
        Instant nextAttempt = (Instant) ReflectionTestUtils.getField(job, "nextDatabaseAttemptAt");
        assertThat(nextAttempt).isAfter(Instant.now());
        job.publishDueMessages();

        verify(claims, times(1)).claimBatch(100);
        verifyNoFailureMutation();
        verifyNoInteractions(chatMessageRepository, chatParticipantRepository, eventPublisher);
        assertThat(databaseErrors()).isEqualTo(1);
        assertThat(lastPollSuccess()).isZero();
    }

    private void verifyNoFailureMutation() {
        verify(claims, never()).retry(anyLong(), anyString(), anyLong(), anyString());
        verify(claims, never()).fail(anyLong(), anyString(), anyString());
    }

    private double resultCount(String outcome) {
        return registry.get("chat.outbox.results").tag("outcome", outcome).counter().count();
    }

    private double databaseErrors() {
        return registry.get("chat.outbox.database.errors").counter().count();
    }

    private double lastPollSuccess() {
        return registry.get("chat.outbox.poll.last.success.unixtime").gauge().value();
    }

    private ChatParticipant participant(Long participantId, Long userId, ChatParticipantStatus status) {
        ChatParticipant participant = new ChatParticipant(room, userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        ReflectionTestUtils.setField(participant, "status", status);
        return participant;
    }
}
