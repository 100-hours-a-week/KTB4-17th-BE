package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class ChatMessageRateLimiterIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final int BURST_CAPACITY = 10;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        connectionFactory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisTemplate = redisTemplate(connectionFactory);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
        connectionFactory.destroy();
    }

    @Test
    void 두_채팅_서버에서_동시에_전송을_요청해도_회원별_전송_한도만_허용한다() throws Exception {
        ChatMessageRateLimiter firstServer = rateLimiter(Duration.ofHours(1));
        ChatMessageRateLimiter secondServer = rateLimiter(Duration.ofHours(1));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(20)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ChatMessageRateLimiter limiter = i % 2 == 0 ? firstServer : secondServer;
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryAcquire(USER_ID);
                }));
            }

            start.countDown();

            long permittedCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    permittedCount++;
                }
            }
            assertThat(permittedCount).isEqualTo(BURST_CAPACITY);
        }
    }

    @Test
    void 전송_제한에_도달한_후_시간이_지나면_다시_전송할_수_있다() {
        ChatMessageRateLimiter limiter = rateLimiter(Duration.ofMillis(100));
        for (int i = 0; i < BURST_CAPACITY; i++) {
            assertThat(limiter.tryAcquire(USER_ID)).isTrue();
        }
        assertThat(limiter.tryAcquire(USER_ID)).isFalse();

        await().atMost(Duration.ofSeconds(1))
            .until(() -> limiter.tryAcquire(USER_ID));
    }

    @Test
    void Redis_장애가_발생해도_메시지_전송을_허용한다() {
        LettuceConnectionFactory unavailableConnectionFactory = connectionFactory("127.0.0.1", 1);
        try {
            ChatMessageRateLimiter limiter = new ChatMessageRateLimiter(
                redisTemplate(unavailableConnectionFactory), meterRegistry);

            assertThat(limiter.tryAcquire(USER_ID)).isTrue();
            assertThat(meterRegistry.get("chat.message.rate_limit.redis.failures")
                .counter().count()).isEqualTo(1);
        } finally {
            unavailableConnectionFactory.destroy();
        }
    }

    private ChatMessageRateLimiter rateLimiter(Duration refillInterval) {
        return new ChatMessageRateLimiter(
            redisTemplate,
            meterRegistry,
            BURST_CAPACITY,
            refillInterval,
            Duration.ofMinutes(10));
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
