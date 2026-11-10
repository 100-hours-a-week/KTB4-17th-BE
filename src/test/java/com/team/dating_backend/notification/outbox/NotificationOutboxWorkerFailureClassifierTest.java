package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.outbox.NotificationOutboxWorkerFailureClassifier.Result;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.transaction.TransactionSystemException;

class NotificationOutboxWorkerFailureClassifierTest {

    private final NotificationOutboxWorkerFailureClassifier classifier = new NotificationOutboxWorkerFailureClassifier(
        new NotificationCreationFailureClassifier());

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void 원인이_확인된_DB와_Delivery_일시_장애는_재시도_대상이다(Throwable failure) {
        assertThat(classifier.classify(failure)).isEqualTo(Result.RETRYABLE);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("nonRetryableFailures")
    void 잘못된_요청과_인증_권한_설정_문제와_미분류_오류는_재시도하지_않는다(Throwable failure) {
        assertThat(classifier.classify(failure)).isEqualTo(Result.NON_RETRYABLE);
    }

    @ParameterizedTest
    @MethodSource("terminalCauses")
    void 일시_장애로_감싸져도_재시도_불가_원인을_우선한다(Throwable cause) {
        assertThat(classifier.classify(new org.apache.kafka.common.errors.TimeoutException(cause)))
            .isEqualTo(Result.NON_RETRYABLE);
    }

    @ParameterizedTest
    @MethodSource("interruptions")
    void 감싸진_인터럽트도_일반_실패와_구분한다(Throwable interruption) {
        assertThat(classifier.classify(new ExecutionException(interruption))).isEqualTo(Result.INTERRUPTED);
    }

    @Test
    void 점유권_상실은_원인이_일시_장애여도_재시도_대상과_구분한다() {
        var lost = new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST, new TimeoutException());

        assertThat(classifier.classify(new ExecutionException(lost))).isEqualTo(Result.CLAIM_LOST);
    }

    @Test
    void 점유권_상실에_중단_원인이_있으면_중단을_우선한다() {
        var lost = new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST,
            new InterruptedException());

        assertThat(classifier.classify(lost)).isEqualTo(Result.INTERRUPTED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void JDBC_추가_예외에서도_재시도_가능_원인과_영구_원인을_검사한다(boolean terminal) {
        var failure = new SQLException();
        failure.setNextException(terminal
            ? new SQLIntegrityConstraintViolationException(null, "23000", 1452)
            : new SQLException(null, "HY000", 1205));

        assertThat(classifier.classify(new QueryTimeoutException(null, failure)))
            .isEqualTo(terminal ? Result.NON_RETRYABLE : Result.RETRYABLE);
    }

    @ParameterizedTest
    @MethodSource("specialFailures")
    void JDBC_추가_예외의_중단과_점유권_상실도_구분한다(Throwable special, Result expected) {
        var failure = new SQLException(null, "40001", 1213);
        failure.setNextException(new SQLException(special));

        assertThat(classifier.classify(failure)).isEqualTo(expected);
    }

    @Test
    void 순환하는_원인과_JDBC_추가_예외가_있어도_종료한다() {
        var first = new SQLException();
        var second = new SQLException();
        first.initCause(second);
        second.initCause(first);
        first.setNextException(second);

        assertThat(classifier.classify(first)).isEqualTo(Result.NON_RETRYABLE);
    }

    @Test
    void 분류는_원본_예외와_스레드의_인터럽트_상태를_변경하지_않는다() {
        var interruption = new InterruptedException();
        var failure = new ExecutionException(interruption);
        try {
            Thread.currentThread().interrupt();

            assertThat(classifier.classify(failure)).isEqualTo(Result.INTERRUPTED);
            assertThat(failure.getCause()).isSameAs(interruption);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static Stream<Throwable> retryableFailures() {
        return Stream.of(new CannotAcquireLockException(null), new QueryTimeoutException(null),
            new RecoverableDataAccessException(null), new SQLRecoverableException(),
            new SQLNonTransientConnectionException(null, "08001"), new SQLException(null, "40001"),
            new SQLException(null, "HY000", 1205), new SQLException(null, null, 1213),
            new TransactionSystemException(null, new SQLException(null, "08S01")),
            new ExecutionException(new org.apache.kafka.common.errors.TimeoutException()),
            new NotEnoughReplicasException(), new TimeoutException());
    }

    private static Stream<Throwable> nonRetryableFailures() {
        return Stream.of(new NotificationInvalidCreateRequestException(),
            new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR),
            new AuthenticationException("auth"), new AuthorizationException("permission"),
            new ConfigException("config"),
            new SerializationException(), new DeserializationException(null, new byte[0], false, null),
            new MessageConversionException("format"), new IllegalArgumentException(),
            new UnsupportedOperationException(),
            new DataAccessResourceFailureException(null), new SQLException(), new SQLException(null, "HY000"),
            new SQLIntegrityConstraintViolationException(null, "23000", 1452), new IllegalStateException(),
            new ExecutionException(new IllegalStateException()), new AssertionError());
    }

    private static Stream<Throwable> terminalCauses() {
        return Stream.of(new NotificationInvalidCreateRequestException(), new AuthorizationException("permission"),
            new ConfigException("config"), new SerializationException(new TimeoutException()),
            new SQLIntegrityConstraintViolationException(null, "23000", 1452),
            new IllegalArgumentException(new TimeoutException()),
            new UnsupportedOperationException(new TimeoutException()));
    }

    private static Stream<Throwable> interruptions() {
        return Stream.of(new InterruptedException(), new InterruptException(new InterruptedException()),
            new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED));
    }

    private static Stream<Arguments> specialFailures() {
        return Stream.of(Arguments.of(new InterruptException(new InterruptedException()), Result.INTERRUPTED),
            Arguments.of(new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST),
                Result.CLAIM_LOST));
    }
}
