package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.entity.ChatMessage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class ChatDelegationSchedulePolicy {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofHours(24);
    private static final Duration MINIMUM_REPLY_DELAY = Duration.ofHours(2);
    private static final Duration MAXIMUM_REPLY_DELAY = Duration.ofHours(5);

    private final RandomSeconds randomSeconds;

    public ChatDelegationSchedulePolicy() {
        this(bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    ChatDelegationSchedulePolicy(RandomSeconds randomSeconds) {
        this.randomSeconds = randomSeconds;
    }

    public LocalDateTime nextActionAt(
        ChatMessage latestMessage, Long requesterParticipantId, LocalDateTime now) {
        LocalDateTime sentAt = latestMessage.getCreatedAt();
        if (requesterParticipantId.equals(latestMessage.getSenderParticipantId())) {
            return laterOf(now, sentAt.plus(RESPONSE_TIMEOUT));
        }

        LocalDateTime windowEnd = sentAt.plus(MAXIMUM_REPLY_DELAY);
        if (!windowEnd.isAfter(now)) {
            return now;
        }
        LocalDateTime windowStart = laterOf(now, sentAt.plus(MINIMUM_REPLY_DELAY));
        long availableSeconds = Duration.between(windowStart, windowEnd).getSeconds();
        long minimumOffset = windowStart.equals(now) ? 1 : 0;
        if (availableSeconds < minimumOffset) {
            return windowEnd;
        }
        long offset = minimumOffset
            + randomSeconds.nextLong(availableSeconds - minimumOffset + 1);
        return windowStart.plusSeconds(offset);
    }

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    @FunctionalInterface
    interface RandomSeconds {
        long nextLong(long bound);
    }
}
