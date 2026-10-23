package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageResponse;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.delegation.service.ChatDelegationConversationService.MessageContext;
import com.team.dating_backend.chat.entity.ChatParticipant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationGenerationPersistenceService {

    private static final int FINAL_TURN = 3;

    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationMessageRepository messageRepository;
    private final ChatDelegationConversationService conversationService;

    @Transactional
    public Optional<GenerationInput> prepareNew(Long delegationId, LocalDateTime now) {
        ChatDelegation delegation = delegationRepository.findWithLockById(delegationId)
            .orElse(null);
        if (delegation == null
            || delegation.getStatus() != ChatDelegationStatus.IN_PROGRESS
            || delegation.getNextActionAt() == null
            || delegation.getNextActionAt().isAfter(now)) {
            return Optional.empty();
        }

        long completedTurns = messageRepository.countByDelegationId(delegationId);
        if (completedTurns >= FINAL_TURN) {
            return Optional.empty();
        }
        int turn = Math.toIntExact(completedTurns + 1);
        Long requesterParticipantId = delegation.getRequesterParticipant().getId();
        MessageContext context = conversationService.latest(
            delegation.getChatRoom().getId(), requesterParticipantId);
        ChatDelegationMessage message = messageRepository.save(
            new ChatDelegationMessage(
                delegation, turn, UUID.randomUUID(), context.messageId()));
        delegation.beginGeneration();
        return Optional.of(input(message, delegation, context));
    }

    @Transactional
    public Optional<GenerationInput> prepareRetry(Long messageId, LocalDateTime now) {
        ChatDelegation delegation = lockActiveDelegation(messageId).orElse(null);
        if (delegation == null) {
            return Optional.empty();
        }
        ChatDelegationMessage message = messageRepository.findWithLockById(messageId)
            .orElse(null);
        if (message == null
            || message.getStatus() != ChatDelegationMessageStatus.RETRY_WAIT
            || message.getNextAttemptAt() == null
            || message.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        MessageContext context = conversationService.upTo(
            delegation.getChatRoom().getId(),
            delegation.getRequesterParticipant().getId(),
            message.getContextMessageId());
        message.resume(context.messageId(), now);
        return Optional.of(input(message, delegation, context));
    }

    @Transactional
    public Optional<GenerationInput> prepareRecovery(
        Long messageId, LocalDateTime deadline, LocalDateTime now) {
        ChatDelegation delegation = lockActiveDelegation(messageId).orElse(null);
        if (delegation == null) {
            return Optional.empty();
        }
        ChatDelegationMessage message = messageRepository.findWithLockById(messageId)
            .orElse(null);
        if (message == null
            || message.getStatus() != ChatDelegationMessageStatus.GENERATING
            || message.getUpdatedAt().isAfter(deadline)) {
            return Optional.empty();
        }
        MessageContext context = conversationService.upTo(
            delegation.getChatRoom().getId(),
            delegation.getRequesterParticipant().getId(),
            message.getContextMessageId());
        message.resume(context.messageId(), now);
        return Optional.of(input(message, delegation, context));
    }

    @Transactional
    public void complete(GenerationInput input, MessageResponse response) {
        generating(input).ifPresent(generating -> generating.message()
            .ready(response.messageId(), response.aiResponse()));
    }

    @Transactional
    public void retryGeneration(GenerationInput input, LocalDateTime now) {
        generating(input).ifPresent(generating -> {
            ChatDelegationMessage message = generating.message();
            LocalDateTime nextAttemptAt = now.plusMinutes(
                message.getGenerationFailureCount() + 1L);
            if (!message.retryGeneration(nextAttemptAt)) {
                generating.delegation().requireRetry();
            }
        });
    }

    @Transactional
    public void retryTransport(GenerationInput input, LocalDateTime now) {
        generating(input).ifPresent(generating -> {
            ChatDelegationMessage message = generating.message();
            LocalDateTime nextAttemptAt = now.plusMinutes(
                message.getTransportRetryCount() + 1L);
            if (!message.retryTransport(nextAttemptAt)) {
                generating.delegation().requireRetry();
            }
        });
    }

    @Transactional
    public void requireRetry(GenerationInput input) {
        generating(input).ifPresent(generating -> {
            generating.message().fail();
            generating.delegation().requireRetry();
        });
    }

    private Optional<Generating> generating(GenerationInput input) {
        return messageRepository.findLockTargetById(input.messageId())
            .flatMap(lockTarget -> delegationRepository
                .findWithLockById(lockTarget.delegationId()))
            .filter(delegation -> delegation.getStatus() == ChatDelegationStatus.IN_PROGRESS)
            .flatMap(delegation -> messageRepository.findWithLockById(input.messageId())
                .filter(message -> message.getStatus() == ChatDelegationMessageStatus.GENERATING)
                .filter(message -> message.getRequestId().equals(input.requestId()))
                .filter(message -> message.getDelegation().getId()
                    .equals(delegation.getId()))
                .map(message -> new Generating(delegation, message)));
    }

    private Optional<ChatDelegation> lockActiveDelegation(Long messageId) {
        return messageRepository.findLockTargetById(messageId)
            .flatMap(lockTarget -> delegationRepository
                .findWithLockById(lockTarget.delegationId()))
            .filter(delegation -> delegation.getStatus() == ChatDelegationStatus.IN_PROGRESS);
    }

    private GenerationInput input(
        ChatDelegationMessage message, ChatDelegation delegation, MessageContext context) {
        ChatParticipant requester = delegation.getRequesterParticipant();
        ChatParticipant target = delegation.getChatRoom().getParticipants().stream()
            .filter(participant -> !participant.getId().equals(requester.getId()))
            .findFirst()
            .orElseThrow(IllegalStateException::new);
        MessageRequest request = new MessageRequest(
            message.getRequestId(), delegation.getChatRoom().getId(), delegation.getId(),
            requester.getUserId().toString(), target.getUserId().toString(),
            delegation.getEndType(), context.messages());
        return new GenerationInput(
            message.getId(), message.getRequestId(), message.getTurn(), request);
    }

    public record GenerationInput(
        Long messageId, UUID requestId, int turn, MessageRequest request) {

        public GenerationInput {
            Objects.requireNonNull(messageId);
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(request);
        }
    }

    private record Generating(
        ChatDelegation delegation,
        ChatDelegationMessage message) {}
}
