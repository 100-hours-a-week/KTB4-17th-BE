package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "app.chat", name = "runtime-enabled", havingValue = "false",
    matchIfMissing = true
)
public class ChatDelegationGenerationJob {

    private static final int MAX_DISPATCH_COUNT = 2;
    private static final int RECOVERY_MINUTES = 2;

    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationMessageRepository messageRepository;
    private final ChatDelegationGenerationWorker worker;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void generateDueMessages() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int remaining = dispatch(
                messageRepository.findIdsByStatus(
                    ChatDelegationMessageStatus.READY, Limit.of(MAX_DISPATCH_COUNT)),
                MAX_DISPATCH_COUNT, worker::deliver);
            if (remaining == 0) {
                return;
            }
            remaining = dispatch(
                messageRepository.findRecoverableIds(
                    ChatDelegationMessageStatus.GENERATING,
                    now.minusMinutes(RECOVERY_MINUTES), Limit.of(remaining)),
                remaining, worker::recover);
            if (remaining == 0) {
                return;
            }
            remaining = dispatch(
                messageRepository.findDueRetryIds(
                    ChatDelegationMessageStatus.RETRY_WAIT, now, Limit.of(remaining)),
                remaining, worker::retry);
            if (remaining == 0) {
                return;
            }
            dispatch(delegationRepository.findDueIds(
                ChatDelegationStatus.IN_PROGRESS, now, Limit.of(remaining)),
                remaining, worker::generate);
        } catch (RuntimeException exception) {
            log.error("Chat delegation scheduling failed. exceptionType={}",
                exception.getClass().getSimpleName());
        }
    }

    private int dispatch(List<Long> ids, int remaining, Consumer<Long> action) {
        int available = remaining;
        for (Long id : ids) {
            try {
                action.accept(id);
                available--;
            } catch (TaskRejectedException exception) {
                return 0;
            }
            if (available == 0) {
                break;
            }
        }
        return available;
    }
}
