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
    void Delivery_토픽_이름만으로_바인딩한다() {
        contextRunner.withPropertyValues(
            "app.notification.kafka.delivery-topic=prod-notification_delivery.v1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                NotificationKafkaProperties properties = context.getBean(NotificationKafkaProperties.class);
                assertThat(properties.getDeliveryTopic()).isEqualTo("prod-notification_delivery.v1");
            });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".", "..", "__consumer_offsets", "topic/name", "토픽", "name with space"})
    void 두_토픽_모두_누락되거나_잘못된_이름이면_시작을_거부한다(String topicName) {

        assertInvalidTopic("delivery-topic", topicName);
    }

    @Test
    void 토픽_이름이_250자이면_시작을_거부한다() {

        assertInvalidTopic("delivery-topic", "a".repeat(250));
    }

    private void assertInvalidTopic(String invalidProperty, String topicName) {
        ApplicationContextRunner runner = contextRunner;
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
