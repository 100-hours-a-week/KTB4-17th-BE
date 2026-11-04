package com.team.dating_backend.profile.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.config.RedisConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProfileCacheRedisConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
        .withUserConfiguration(RedisConfiguration.class, ProfileCacheRedisConfiguration.class,
            ConsumerConfiguration.class);

    @Test
    void 두_연결을_등록해도_기존_Redis를_기본으로_주입한다() {
        runner.withPropertyValues("spring.data.redis.url=redis://127.0.0.1:16379").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(2);
            assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(2);
            RedisConsumers consumers = context.getBean(RedisConsumers.class);
            assertThat(consumers.connectionFactory()).isSameAs(context.getBean("redisConnectionFactory"));
            assertThat(consumers.defaultTemplate()).isSameAs(context.getBean("stringRedisTemplate"));
            assertThat(consumers.cacheTemplate()).isSameAs(context.getBean("profileCacheStringRedisTemplate"));
            assertThat(consumers.defaultTemplate().getConnectionFactory()).isSameAs(consumers.connectionFactory());
            assertThat(consumers.cacheTemplate().getConnectionFactory())
                .isSameAs(context.getBean("profileCacheRedisConnectionFactory"));
            LettuceConnectionFactory cache = context.getBean("profileCacheRedisConnectionFactory",
                LettuceConnectionFactory.class);
            assertThat(cache.getHostName()).isEqualTo("localhost");
            assertThat(cache.getPort()).isEqualTo(6380);
        });
    }

    @Test
    void 기존과_캐시의_URL_인증_DB_TLS_타임아웃을_독립적으로_반영한다() {
        runner.withPropertyValues(
            "spring.data.redis.url=rediss://chat:chat%3Apassword@chat.example:16379/2",
            "spring.data.redis.timeout=700ms",
            "spring.data.redis.connect-timeout=500ms",
            "app.profile-cache.redis.url=rediss://cache:cache%3Apassword@cache.example:16380/3",
            "app.profile-cache.redis.connect-timeout=350ms",
            "app.profile-cache.redis.command-timeout=250ms").run(context -> {
                assertThat(context).hasNotFailed();
                LettuceConnectionFactory chat = context.getBean("redisConnectionFactory",
                    LettuceConnectionFactory.class);
                LettuceConnectionFactory cache = context.getBean("profileCacheRedisConnectionFactory",
                    LettuceConnectionFactory.class);
                assertThat(chat.getHostName()).isEqualTo("chat.example");
                assertThat(chat.getPort()).isEqualTo(16379);
                assertThat(chat.getDatabase()).isEqualTo(2);
                assertThat(chat.getStandaloneConfiguration().getUsername()).isEqualTo("chat");
                assertThat(chat.getStandaloneConfiguration().getPassword().get())
                    .containsExactly("chat:password".toCharArray());
                assertThat(cache.getHostName()).isEqualTo("cache.example");
                assertThat(cache.getPort()).isEqualTo(16380);
                assertThat(cache.getDatabase()).isEqualTo(3);
                assertThat(cache.getStandaloneConfiguration().getUsername()).isEqualTo("cache");
                assertThat(cache.getStandaloneConfiguration().getPassword().get())
                    .containsExactly("cache:password".toCharArray());
                assertThat(chat.getClientConfiguration().isUseSsl()).isTrue();
                assertThat(cache.getClientConfiguration().isUseSsl()).isTrue();
                assertThat(chat.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(700));
                assertThat(cache.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
                assertThat(chat.getClientConfiguration().getClientOptions().orElseThrow().getSocketOptions()
                    .getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
                assertThat(cache.getClientConfiguration().getClientOptions().orElseThrow().getSocketOptions()
                    .getConnectTimeout()).isEqualTo(Duration.ofMillis(350));
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"connect-timeout=0ms", "connect-timeout=-1ms", "command-timeout=0ms", "command-timeout=-1ms"}
    )
    void 캐시_타임아웃이_양수가_아니면_시작을_거부한다(String property) {
        runner.withPropertyValues("app.profile-cache.redis." + property)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cache.example:6379", "redis://", "rediss://cache.example:6379/#fragment"})
    void 지원하지_않는_캐시_URL은_시작을_거부한다(String url) {
        runner.withPropertyValues("app.profile-cache.redis.url=" + url).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage(
                "Profile cache Redis URL must be a valid redis:// or rediss:// URL");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfiguration {
        @Bean
        RedisConsumers redisConsumers(RedisConnectionFactory factory, StringRedisTemplate defaultTemplate,
            @Qualifier("profileCacheStringRedisTemplate") StringRedisTemplate cacheTemplate) {
            return new RedisConsumers(factory, defaultTemplate, cacheTemplate);
        }
    }

    record RedisConsumers(RedisConnectionFactory connectionFactory, StringRedisTemplate defaultTemplate,
        StringRedisTemplate cacheTemplate) {}
}
