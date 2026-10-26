package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationKafkaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void 서로_다른_유효한_토픽_이름을_바인딩한다() {
        contextRunner.withPropertyValues(
            "app.notification.kafka.create-requested-topic=" + "a".repeat(249),
            "app.notification.kafka.delivery-topic=prod-notification_delivery.v1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                NotificationKafkaProperties properties = context.getBean(NotificationKafkaProperties.class);
                assertThat(properties.getCreateRequestedTopic()).isEqualTo("a".repeat(249));
                assertThat(properties.getDeliveryTopic()).isEqualTo("prod-notification_delivery.v1");
            });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".", "..", "__consumer_offsets", "topic/name", "토픽", "name with space"})
    void 두_토픽_모두_누락되거나_잘못된_이름이면_시작을_거부한다(String topicName) {
        assertInvalidTopic("create-requested-topic", "delivery-topic", topicName);
        assertInvalidTopic("delivery-topic", "create-requested-topic", topicName);
    }

    @Test
    void 토픽_이름이_250자이면_시작을_거부한다() {
        assertInvalidTopic("create-requested-topic", "delivery-topic", "a".repeat(250));
        assertInvalidTopic("delivery-topic", "create-requested-topic", "a".repeat(250));
    }

    @Test
    void 생성과_delivery가_같은_토픽이면_시작을_거부한다() {
        assertBindingFailure(contextRunner.withPropertyValues(
            "app.notification.kafka.create-requested-topic=same-topic",
            "app.notification.kafka.delivery-topic=same-topic"));
    }

    private void assertInvalidTopic(String invalidProperty, String validProperty, String topicName) {
        ApplicationContextRunner runner = contextRunner.withPropertyValues(
            "app.notification.kafka." + validProperty + "=valid-topic");
        if (topicName != null) {
            runner = runner.withPropertyValues("app.notification.kafka." + invalidProperty + "=" + topicName);
        }
        assertBindingFailure(runner);
    }

    private void assertBindingFailure(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationKafkaProperties.class)
    static class PropertiesConfiguration {}
}
