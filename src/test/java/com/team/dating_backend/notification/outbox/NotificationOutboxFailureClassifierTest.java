package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.enums.NotificationFailureCode;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationOutboxFailureClassifierTest {

    private final NotificationOutboxFailureClassifier classifier = new NotificationOutboxFailureClassifier();

    @ParameterizedTest
    @MethodSource("failures")
    void 발행_오류의_원인을_보존하며_확인_필요_오류와_결과_미확인을_구분한다(Exception failure, NotificationFailureCode code) {
        assertThat(classifier.classify(failure)).isEqualTo(code);
    }

    @Test
    void 원인_순환이_있어도_종료한다() {
        var first = new IllegalStateException();
        var second = new RuntimeException(first);
        first.initCause(second);

        assertThat(classifier.classify(first)).isEqualTo(NotificationFailureCode.PUBLISH_CALL_FAILED);
    }

    private static Stream<Arguments> failures() {
        var timeoutWithPermissionFailure = new TimeoutException("timeout");
        timeoutWithPermissionFailure.initCause(new AuthorizationException("permission"));
        return Stream.of(
            Arguments.of(new TimeoutException(), NotificationFailureCode.PUBLISH_RESULT_TIMEOUT),
            Arguments.of(new ExecutionException(new org.apache.kafka.common.errors.TimeoutException()),
                NotificationFailureCode.PUBLISH_RESULT_TIMEOUT),
            Arguments.of(new ExecutionException(new IllegalStateException()),
                NotificationFailureCode.PUBLISH_RESULT_FAILED),
            Arguments.of(new IllegalStateException(), NotificationFailureCode.PUBLISH_CALL_FAILED),
            Arguments.of(new SerializationException(), NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION),
            Arguments.of(new ExecutionException(new AuthenticationException("auth")),
                NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION),
            Arguments.of(new AuthorizationException("permission"),
                NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION),
            Arguments.of(new ConfigException("config"), NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION),
            Arguments.of(new IllegalArgumentException(), NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION),
            Arguments.of(new ExecutionException(timeoutWithPermissionFailure),
                NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION));
    }
}
