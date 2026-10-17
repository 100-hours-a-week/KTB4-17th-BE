package com.team.dating_backend.chat.presence;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatRoomPresenceRegistry {

    public static final String SESSION_CLOSED_ATTRIBUTE = ChatRoomPresenceRegistry.class.getName()
        + ".closed";

    private static final long PRESENCE_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private final ConcurrentHashMap<Long, Map<String, Presence>> presences = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public ChatRoomPresenceRegistry() {
        this(System::nanoTime);
    }

    ChatRoomPresenceRegistry(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public void enter(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        presences.compute(userId, (id, existing) -> {
            Map<String, Presence> userPresences = existing == null ? new HashMap<>() : existing;
            userPresences.put(sessionId, new Presence(chatRoomId, nanoTime.getAsLong()));
            return userPresences;
        });
    }

    public void heartbeat(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        presences.computeIfPresent(userId, (id, userPresences) -> {
            Presence presence = userPresences.get(sessionId);
            if (presence != null && presence.chatRoomId().equals(chatRoomId)) {
                long now = nanoTime.getAsLong();
                if (isExpired(presence, now)) {
                    userPresences.remove(sessionId);
                } else {
                    userPresences.put(sessionId, new Presence(chatRoomId, now));
                }
            }
            return userPresences.isEmpty() ? null : userPresences;
        });
    }

    public void leave(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        presences.computeIfPresent(userId, (id, userPresences) -> {
            Presence presence = userPresences.get(sessionId);
            if (presence != null && presence.chatRoomId().equals(chatRoomId)) {
                userPresences.remove(sessionId);
            }
            return userPresences.isEmpty() ? null : userPresences;
        });
    }

    public void removeConnection(Long userId, String sessionId) {
        requireConnection(userId, sessionId);

        presences.computeIfPresent(userId, (id, userPresences) -> {
            userPresences.remove(sessionId);
            return userPresences.isEmpty() ? null : userPresences;
        });
    }

    public boolean isViewingChatRoom(Long userId, Long chatRoomId) {
        requirePositiveId(userId);
        requirePositiveId(chatRoomId);

        boolean[] viewing = {false};
        presences.computeIfPresent(userId, (id, userPresences) -> {
            long now = nanoTime.getAsLong();
            userPresences.values().removeIf(presence -> isExpired(presence, now));
            viewing[0] = userPresences.values().stream()
                .anyMatch(presence -> presence.chatRoomId().equals(chatRoomId));
            return userPresences.isEmpty() ? null : userPresences;
        });
        return viewing[0];
    }

    @Scheduled(fixedDelay = 30_000)
    public void evictExpiredPresences() {
        for (Long userId : presences.keySet()) {
            presences.computeIfPresent(userId, (id, userPresences) -> {
                long now = nanoTime.getAsLong();
                userPresences.values().removeIf(presence -> isExpired(presence, now));
                return userPresences.isEmpty() ? null : userPresences;
            });
        }
    }

    private boolean isExpired(Presence presence, long now) {
        return now - presence.lastSeenNanos() >= PRESENCE_TTL_NANOS;
    }

    private void requireConnection(Long userId, String sessionId) {
        requirePositiveId(userId);
        if (!StringUtils.hasText(sessionId)) {
            throw new ChatBusinessException(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new ChatBusinessException(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private record Presence(Long chatRoomId, long lastSeenNanos) {}
}
