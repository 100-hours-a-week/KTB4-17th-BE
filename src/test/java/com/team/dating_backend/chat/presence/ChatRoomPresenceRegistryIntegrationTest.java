package com.team.dating_backend.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ChatRoomPresenceRegistryIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long CHAT_ROOM_ID = 10L;
    private static final String SESSION_KEY = "chat:presence:{1}:session:pc";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisTemplate = redisTemplate(connectionFactory);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void 여러_채팅_서버가_접속_상태를_공유한다() {
        ChatRoomPresenceRegistry firstServer = registry(redisTemplate);
        ChatRoomPresenceRegistry secondServer = registry(redisTemplate);

        firstServer.enter(USER_ID, "pc", CHAT_ROOM_ID);
        secondServer.enter(USER_ID, "mobile", CHAT_ROOM_ID);
        assertThat(secondServer.isViewingChatRoom(USER_ID, CHAT_ROOM_ID)).isTrue();

        firstServer.enter(USER_ID, "pc", 20L);
        firstServer.leave(USER_ID, "pc", CHAT_ROOM_ID);
        assertThat(secondServer.isViewingChatRoom(USER_ID, 20L)).isTrue();

        firstServer.removeConnection(USER_ID, "pc");
        assertThat(firstServer.isViewingChatRoom(USER_ID, 20L)).isFalse();
        assertThat(firstServer.isViewingChatRoom(USER_ID, CHAT_ROOM_ID)).isTrue();

        secondServer.leave(USER_ID, "mobile", CHAT_ROOM_ID);
        assertThat(secondServer.isViewingChatRoom(USER_ID, CHAT_ROOM_ID)).isFalse();
    }

    @Test
    void 접속_상태를_갱신하면_만료_시간이_연장된다() {
        ChatRoomPresenceRegistry registry = registry(redisTemplate);
        registry.enter(USER_ID, "pc", CHAT_ROOM_ID);
        redisTemplate.expire(SESSION_KEY, Duration.ofMillis(100));

        registry.heartbeat(USER_ID, "pc", CHAT_ROOM_ID);

        Long remainingTtl = redisTemplate.getExpire(SESSION_KEY, TimeUnit.MILLISECONDS);
        assertThat(remainingTtl).isBetween(1_000L, 2_000L);

        redisTemplate.delete(SESSION_KEY);
        registry.heartbeat(USER_ID, "pc", CHAT_ROOM_ID);

        assertThat(registry.isViewingChatRoom(USER_ID, CHAT_ROOM_ID)).isFalse();
    }

    @Test
    void Redis_장애가_발생하면_사용자를_미접속으로_판단한다() {
        LettuceConnectionFactory unavailableConnectionFactory = connectionFactory("127.0.0.1", 1);
        try {
            ChatRoomPresenceRegistry registry = registry(
                redisTemplate(unavailableConnectionFactory));

            assertThatCode(() -> registry.enter(USER_ID, "pc", CHAT_ROOM_ID))
                .doesNotThrowAnyException();
            assertThat(registry.isViewingChatRoom(USER_ID, CHAT_ROOM_ID)).isFalse();
        } finally {
            unavailableConnectionFactory.destroy();
        }
    }

    private ChatRoomPresenceRegistry registry(StringRedisTemplate template) {
        return new ChatRoomPresenceRegistry(template, Duration.ofSeconds(2));
    }

    private LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        return factory;
    }

    private StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
