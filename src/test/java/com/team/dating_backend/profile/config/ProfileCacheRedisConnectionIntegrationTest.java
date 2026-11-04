package com.team.dating_backend.profile.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.service.AiPracticeRedisEventSubscriber;
import com.team.dating_backend.chat.config.ChatRealtimeRedisConfiguration;
import com.team.dating_backend.chat.service.ChatMessageReadReceiptRedisSubscriber;
import com.team.dating_backend.chat.service.ChatMessageRedisSubscriber;
import com.team.dating_backend.common.config.RedisConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ProfileCacheRedisConnectionIntegrationTest {

    @Container
    static final GenericContainer<?> CHAT = redis();

    @Container
    static final GenericContainer<?> CACHE = redis();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                DataRedisReactiveAutoConfiguration.class, DataRedisHealthContributorAutoConfiguration.class,
                DataRedisReactiveHealthContributorAutoConfiguration.class, HealthContributorAutoConfiguration.class,
                HealthContributorRegistryAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
            .withUserConfiguration(RedisConfiguration.class, ProfileCacheRedisConfiguration.class)
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                        .forEach(context.getEnvironment().getPropertySources()::addLast);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            })
            .withPropertyValues("spring.data.redis.url=" + url(CHAT),
                "app.profile-cache.redis.url=" + url(CACHE));
    }

    @Test
    void 같은_키를_저장해도_두_Redis의_데이터는_독립적이다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            StringRedisTemplate chat = context.getBean("stringRedisTemplate", StringRedisTemplate.class);
            StringRedisTemplate cache = context.getBean("profileCacheStringRedisTemplate", StringRedisTemplate.class);
            chat.opsForValue().set("redis-routing-test", "chat");
            cache.opsForValue().set("redis-routing-test", "cache");

            assertThat(chat.opsForValue().get("redis-routing-test")).isEqualTo("chat");
            assertThat(cache.opsForValue().get("redis-routing-test")).isEqualTo("cache");
            HealthEndpoint health = context.getBean(HealthEndpoint.class);
            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
            assertThat(health.healthForPath("service").getStatus()).isEqualTo(Status.UP);
            assertThat(health.healthForPath("cache").getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test
    void 캐시_Redis에_연결할_수_없어도_시작과_기존_Redis_접근은_가능하다() {
        runner().withPropertyValues("app.profile-cache.redis.url=redis://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed();
            StringRedisTemplate chat = context.getBean("stringRedisTemplate", StringRedisTemplate.class);
            chat.opsForValue().set("cache-unavailable-test", "chat-available");
            assertThat(chat.opsForValue().get("cache-unavailable-test")).isEqualTo("chat-available");
            HealthEndpoint health = context.getBean(HealthEndpoint.class);
            assertThat(health.healthForPath("service").getStatus()).isEqualTo(Status.UP);
            assertThat(health.healthForPath("cache").getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void 기존_Redis_장애는_서비스_상태_판정에_계속_반영한다() {
        runner().withPropertyValues("spring.data.redis.url=redis://127.0.0.1:1",
            "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms").run(context -> {
                assertThat(context).hasNotFailed();
                HealthEndpoint health = context.getBean(HealthEndpoint.class);
                assertThat(health.healthForPath("cache").getStatus()).isEqualTo(Status.UP);
                assertThat(health.healthForPath("service").getStatus()).isEqualTo(Status.DOWN);
            });
    }

    @Test
    void 채팅_구독_컨테이너는_기존_Redis의_이벤트를_수신한다() {
        ChatMessageRedisSubscriber subscriber = mock(ChatMessageRedisSubscriber.class);
        runner().withUserConfiguration(ChatRealtimeRedisConfiguration.class)
            .withBean(ChatMessageRedisSubscriber.class, () -> subscriber)
            .withBean(ChatMessageReadReceiptRedisSubscriber.class,
                () -> mock(ChatMessageReadReceiptRedisSubscriber.class))
            .withBean(AiPracticeRedisEventSubscriber.class, () -> mock(AiPracticeRedisEventSubscriber.class))
            .withPropertyValues("app.chat.runtime-enabled=true", "app.chat.realtime-relay.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                RedisMessageListenerContainer listener = context.getBean("chatRedisMessageListenerContainer",
                    RedisMessageListenerContainer.class);
                assertThat(listener.getConnectionFactory()).isSameAs(context.getBean("redisConnectionFactory"));
                context.getBean("stringRedisTemplate", StringRedisTemplate.class)
                    .convertAndSend("chat:message-created:v1", "routing-test");
                verify(subscriber, timeout(3000)).onMessage(any(), any());
            });
    }

    private static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    private static String url(GenericContainer<?> redis) {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }
}
