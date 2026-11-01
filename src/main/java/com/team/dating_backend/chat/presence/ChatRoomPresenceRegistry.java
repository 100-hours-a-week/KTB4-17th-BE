package com.team.dating_backend.chat.presence;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class ChatRoomPresenceRegistry {

    public static final String SESSION_CLOSED_ATTRIBUTE = ChatRoomPresenceRegistry.class.getName()
        + ".closed";

    private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "chat:presence:";

    private static final DefaultRedisScript<Long> ENTER_SCRIPT = script("""
        redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
        redis.call('SADD', KEYS[1], ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        return 1
        """);

    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = script("""
        local roomId = redis.call('GET', KEYS[2])
        if not roomId then
            redis.call('SREM', KEYS[1], ARGV[1])
            if redis.call('SCARD', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            end
            return 0
        end
        if roomId ~= ARGV[2] then
            return 0
        end
        redis.call('PEXPIRE', KEYS[2], ARGV[3])
        redis.call('SADD', KEYS[1], ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        return 1
        """);

    private static final DefaultRedisScript<Long> LEAVE_SCRIPT = script("""
        local roomId = redis.call('GET', KEYS[2])
        if roomId ~= ARGV[2] then
            return 0
        end
        redis.call('DEL', KEYS[2])
        redis.call('SREM', KEYS[1], ARGV[1])
        if redis.call('SCARD', KEYS[1]) == 0 then
            redis.call('DEL', KEYS[1])
        end
        return 1
        """);

    private static final DefaultRedisScript<Long> REMOVE_CONNECTION_SCRIPT = script("""
        redis.call('DEL', KEYS[2])
        redis.call('SREM', KEYS[1], ARGV[1])
        if redis.call('SCARD', KEYS[1]) == 0 then
            redis.call('DEL', KEYS[1])
        end
        return 1
        """);

    private static final DefaultRedisScript<Long> IS_VIEWING_SCRIPT = script("""
        local sessions = redis.call('SMEMBERS', KEYS[1])
        local isViewing = 0
        for _, sessionId in ipairs(sessions) do
            local sessionKey = KEYS[2] .. sessionId
            local roomId = redis.call('GET', sessionKey)
            if not roomId then
                redis.call('SREM', KEYS[1], sessionId)
            elseif roomId == ARGV[1] then
                isViewing = 1
            end
        end
        if redis.call('SCARD', KEYS[1]) == 0 then
            redis.call('DEL', KEYS[1])
        end
        return isViewing
        """);

    private final StringRedisTemplate redisTemplate;
    private final long presenceTtlMillis;

    @Autowired
    public ChatRoomPresenceRegistry(StringRedisTemplate redisTemplate) {
        this(redisTemplate, PRESENCE_TTL);
    }

    ChatRoomPresenceRegistry(StringRedisTemplate redisTemplate, Duration presenceTtl) {
        this.redisTemplate = redisTemplate;
        this.presenceTtlMillis = presenceTtl.toMillis();
    }

    public void enter(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        executeSafely(
            ENTER_SCRIPT,
            sessionKeys(userId, sessionId),
            sessionId,
            chatRoomId.toString(),
            Long.toString(presenceTtlMillis));
    }

    public void heartbeat(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        executeSafely(
            HEARTBEAT_SCRIPT,
            sessionKeys(userId, sessionId),
            sessionId,
            chatRoomId.toString(),
            Long.toString(presenceTtlMillis));
    }

    public void leave(Long userId, String sessionId, Long chatRoomId) {
        requireConnection(userId, sessionId);
        requirePositiveId(chatRoomId);

        executeSafely(
            LEAVE_SCRIPT,
            sessionKeys(userId, sessionId),
            sessionId,
            chatRoomId.toString());
    }

    public void removeConnection(Long userId, String sessionId) {
        requireConnection(userId, sessionId);

        executeSafely(
            REMOVE_CONNECTION_SCRIPT,
            sessionKeys(userId, sessionId),
            sessionId);
    }

    public boolean isViewingChatRoom(Long userId, Long chatRoomId) {
        requirePositiveId(userId);
        requirePositiveId(chatRoomId);

        try {
            Long result = redisTemplate.execute(
                IS_VIEWING_SCRIPT,
                List.of(userSessionsKey(userId), sessionKeyPrefix(userId)),
                chatRoomId.toString());
            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException exception) {
            log.warn("채팅방 접속 상태를 조회할 수 없어 미접속 상태로 처리합니다. userId={}, chatRoomId={}",
                userId, chatRoomId, exception);
            return false;
        }
    }

    private void executeSafely(
        DefaultRedisScript<Long> script, List<String> keys, String... arguments) {
        try {
            redisTemplate.execute(script, keys, (Object[]) arguments);
        } catch (DataAccessException exception) {
            log.warn("채팅방 접속 상태를 Redis에 반영하지 못했습니다.", exception);
        }
    }

    private List<String> sessionKeys(Long userId, String sessionId) {
        return List.of(userSessionsKey(userId), sessionKeyPrefix(userId) + sessionId);
    }

    private String userSessionsKey(Long userId) {
        return KEY_PREFIX + "{" + userId + "}:sessions";
    }

    private String sessionKeyPrefix(Long userId) {
        return KEY_PREFIX + "{" + userId + "}:session:";
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

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
