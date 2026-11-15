package com.team.dating_backend.profile.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProfileCachePropertiesTest {

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
    void 기본값은_캐시_활성화_3분_TTL_10초_여유이며_OSIV는_비활성화다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ProfileCacheProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getMaxTtl()).isEqualTo(Duration.ofMinutes(3));
            assertThat(properties.getImageRequestBuffer()).isEqualTo(Duration.ofSeconds(10));
            assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        });
    }

    @Test
    void 환경변수로_사용여부_TTL_여유시간을_설정한다() {
        runner.withPropertyValues("PROFILE_CACHE_ENABLED=false", "PROFILE_CACHE_MAX_TTL=2m",
            "PROFILE_CACHE_IMAGE_REQUEST_BUFFER=5s").run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(ProfileCacheProperties.class);
                assertThat(properties.isEnabled()).isFalse();
                assertThat(properties.getMaxTtl()).isEqualTo(Duration.ofMinutes(2));
                assertThat(properties.getImageRequestBuffer()).isEqualTo(Duration.ofSeconds(5));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-ttl=0ms", "max-ttl=-1ms", "image-request-buffer=-1ms"})
    void 잘못된_시간설정은_오류코드로_시작을_거부한다(String property) {
        runner.withPropertyValues("app.profile-cache." + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOfSatisfying(BindValidationException.class,
                failure -> assertThat(failure.getValidationErrors().getAllErrors())
                    .allSatisfy(error -> assertThat(error.getDefaultMessage()).isEqualTo("INTERNAL_SERVER_ERROR")));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProfileCacheProperties.class)
    static class PropertiesConfiguration {}
}
