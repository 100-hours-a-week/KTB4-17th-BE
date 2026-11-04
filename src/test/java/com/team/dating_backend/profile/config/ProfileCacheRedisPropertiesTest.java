package com.team.dating_backend.profile.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProfileCacheRedisPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(sources::addLast);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });

    @Test
    void 캐시_기본값은_기존_Redis와_분리된_주소와_짧은_타임아웃이다() {
        runner.withPropertyValues("REDIS_URL=redis://existing.example:16379").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ProfileCacheRedisProperties.class);
            assertThat(properties.getUrl()).isEqualTo("redis://localhost:6380");
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(300));
            assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofMillis(200));
        });
    }

    @Test
    void 캐시_환경변수를_전용_설정에_반영한다() {
        runner.withPropertyValues("PROFILE_CACHE_REDIS_URL=rediss://cache.example:16380/3",
            "PROFILE_CACHE_REDIS_CONNECT_TIMEOUT=450ms", "PROFILE_CACHE_REDIS_COMMAND_TIMEOUT=250ms")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(ProfileCacheRedisProperties.class);
                assertThat(properties.getUrl()).isEqualTo("rediss://cache.example:16380/3");
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(450));
                assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProfileCacheRedisProperties.class)
    static class PropertiesConfiguration {}
}
