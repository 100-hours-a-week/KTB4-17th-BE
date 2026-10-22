package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.delegation.service.ChatDelegationConversationService.MessageContext;
import com.team.dating_backend.chat.delegation.service.ChatDelegationGenerationPersistenceService.GenerationInput;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationGenerationPersistenceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

    private ChatDelegationRepository delegationRepository;
    private ChatDelegationMessageRepository messageRepository;
    private ChatDelegationConversationService conversationService;
    private ChatDelegationGenerationPersistenceService service;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        messageRepository = mock(ChatDelegationMessageRepository.class);
        conversationService = mock(ChatDelegationConversationService.class);
        service = new ChatDelegationGenerationPersistenceService(
            delegationRepository, messageRepository, conversationService);
    }

    @Test
    void AI_답장_생성_작업을_시작하고_결과를_저장한다() {
        Fixture fixture = fixture(20L);
        ChatDelegation delegation = fixture.delegation();
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(delegation));
        when(messageRepository.countByDelegationId(20L)).thenReturn(0L);
        when(conversationService.latest(10L, fixture.requester().getId()))
            .thenReturn(context(101L));
        when(messageRepository.save(any())).thenAnswer(invocation -> {
            ChatDelegationMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 30L);
            return message;
        });

        GenerationInput input = service.prepareNew(20L, NOW).orElseThrow();

        assertThat(input.turn()).isEqualTo(1);
        assertThat(input.request().userId()).isEqualTo("1");
        assertThat(input.request().targetUserId()).isEqualTo("2");
        assertThat(delegation.getNextActionAt()).isNull();
        assertThat(service.prepareNew(20L, NOW)).isEmpty();
        verify(messageRepository, times(1)).save(any());

        ChatDelegationMessage saved = capturedMessage();
        when(messageRepository.findWithLockById(30L)).thenReturn(Optional.of(saved));
        MessageResponse response = new MessageResponse(
            10L, 50L, "답장", "SUCCESS", 1, false, 1);
        service.complete(input, response);

        assertThat(saved.getStatus()).isEqualTo(ChatDelegationMessageStatus.READY);
        assertThat(saved.getContent()).isEqualTo("답장");
    }

    @Test
    void 실패_유형에_따라_답장_생성을_재시도한다() {
        Fixture fixture = fixture(20L);
        ChatDelegationMessage generation = message(fixture.delegation(), 30L, 101L);
        UUID generationRequestId = generation.getRequestId();
        generation.retryGeneration(NOW.minusMinutes(1));
        when(messageRepository.findWithLockById(30L)).thenReturn(Optional.of(generation));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));
        when(conversationService.latest(10L, fixture.requester().getId()))
            .thenReturn(context(105L));

        GenerationInput regenerated = service.prepareRetry(30L, NOW).orElseThrow();

        assertThat(regenerated.requestId()).isNotEqualTo(generationRequestId);
        assertThat(generation.getContextMessageId()).isEqualTo(105L);

        ChatDelegationMessage transport = message(fixture.delegation(), 31L, 105L);
        UUID transportRequestId = transport.getRequestId();
        transport.retryTransport(NOW.minusMinutes(1));
        when(messageRepository.findWithLockById(31L)).thenReturn(Optional.of(transport));
        when(conversationService.upTo(10L, fixture.requester().getId(), 105L))
            .thenReturn(context(105L));

        GenerationInput transported = service.prepareRetry(31L, NOW).orElseThrow();

        assertThat(transported.requestId()).isEqualTo(transportRequestId);
        verify(conversationService).upTo(10L, fixture.requester().getId(), 105L);
    }

    @Test
    void 재시도_한도에_도달하면_실패_종류에_맞게_위임을_종료한다() {
        Fixture generationFixture = fixture(20L);
        ChatDelegationMessage generation = message(
            generationFixture.delegation(), 30L, 101L);
        prepareThirdGenerationAttempt(generation);
        when(messageRepository.findWithLockById(30L)).thenReturn(Optional.of(generation));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(generationFixture.delegation()));
        service.retryGeneration(input(generation), NOW);

        assertThat(generationFixture.delegation().getStatus())
            .isEqualTo(ChatDelegationStatus.FAILED);

        Fixture transportFixture = fixture(21L);
        ChatDelegationMessage transport = message(
            transportFixture.delegation(), 31L, 101L);
        prepareThirdTransportAttempt(transport);
        when(messageRepository.findWithLockById(31L)).thenReturn(Optional.of(transport));
        when(delegationRepository.findWithLockById(21L))
            .thenReturn(Optional.of(transportFixture.delegation()));
        service.retryTransport(input(transport), NOW);

        assertThat(transportFixture.delegation().getStatus())
            .isEqualTo(ChatDelegationStatus.RETRY_REQUIRED);
    }

    @Test
    void 처리_중단_작업은_기존_요청과_대화로_복구한다() {
        Fixture fixture = fixture(20L);
        ChatDelegationMessage message = message(fixture.delegation(), 30L, 101L);
        UUID requestId = message.getRequestId();
        ReflectionTestUtils.setField(message, "updatedAt", NOW.minusMinutes(3));
        when(messageRepository.findWithLockById(30L)).thenReturn(Optional.of(message));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));
        when(conversationService.upTo(10L, fixture.requester().getId(), 101L))
            .thenReturn(context(101L));

        GenerationInput recovered = service.prepareRecovery(
            30L, NOW.minusMinutes(2), NOW).orElseThrow();

        assertThat(recovered.requestId()).isEqualTo(requestId);
        assertThat(message.getUpdatedAt()).isEqualTo(NOW);
        assertThat(message.getTransportRetryCount()).isZero();
        assertThat(message.getGenerationFailureCount()).isZero();
    }

    private ChatDelegationMessage capturedMessage() {
        return (ChatDelegationMessage) org.mockito.Mockito.mockingDetails(messageRepository)
            .getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("save"))
            .findFirst()
            .orElseThrow()
            .getArgument(0);
    }

    private void prepareThirdGenerationAttempt(ChatDelegationMessage message) {
        message.retryGeneration(NOW);
        message.resume(102L, NOW);
        message.retryGeneration(NOW);
        message.resume(103L, NOW);
    }

    private void prepareThirdTransportAttempt(ChatDelegationMessage message) {
        message.retryTransport(NOW);
        message.resume(101L, NOW);
        message.retryTransport(NOW);
        message.resume(101L, NOW);
    }

    private GenerationInput input(ChatDelegationMessage message) {
        return new GenerationInput(
            message.getId(), message.getRequestId(), message.getTurn(),
            mock(com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageRequest.class));
    }

    private ChatDelegationMessage message(
        ChatDelegation delegation, Long id, Long contextMessageId) {
        ChatDelegationMessage message = new ChatDelegationMessage(
            delegation, 1, UUID.randomUUID(), contextMessageId);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private MessageContext context(Long messageId) {
        return new MessageContext(messageId,
            List.of(new RecentMessage(Speaker.TARGET, "최근 대화")));
    }

    private Fixture fixture(Long delegationId) {
        Match match = new Match(1L, 2L, NOW.minusDays(1));
        ReflectionTestUtils.setField(match, "id", delegationId);
        ChatRoom room = new ChatRoom(match, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        ChatParticipant requester = room.addParticipant(1L);
        ReflectionTestUtils.setField(requester, "id", 11L);
        ChatParticipant target = room.addParticipant(2L);
        ReflectionTestUtils.setField(target, "id", 12L);
        UUID draftRequestId = UUID.randomUUID();
        ChatDelegation delegation = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, draftRequestId, 100L);
        ReflectionTestUtils.setField(delegation, "id", delegationId);
        delegation.completeDraft(draftRequestId, "a".repeat(32));
        delegation.start("a".repeat(32), NOW.minusHours(1), NOW.minusMinutes(1));
        return new Fixture(delegation, requester);
    }

    private record Fixture(ChatDelegation delegation, ChatParticipant requester) {}
}
