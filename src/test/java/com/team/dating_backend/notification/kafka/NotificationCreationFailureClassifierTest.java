package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class NotificationCreationFailureClassifierTest {

    private final NotificationCreationFailureClassifier classifier = new NotificationCreationFailureClassifier();

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void 원인이_확인되는_일시_DB_장애와_전파_실패는_재시도한다(Throwable failure) {
        assertThat(classifier.isRetryable(failure)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("nonRetryableFailures")
    void 요청_영구_오류와_원인이_불명확한_실패는_재시도하지_않는다(Throwable failure) {
        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("terminalCauses")
    void 바깥_예외가_일시_실패여도_안쪽_영구_원인을_우선한다(Throwable cause) {
        var failure = new org.apache.kafka.common.errors.TimeoutException(cause);

        assertThat(classifier.isRetryable(failure)).isFalse();
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0A000", "22001", "23000", "28000", "3D000", "3F000", "42000"})
    void SQLState의_영구_원인을_Spring의_일시_오류보다_우선한다(String sqlState) {
        var failure = new QueryTimeoutException(null, new SQLException(null, sqlState));

        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @Test
    void JDBC_추가_예외에_있는_일시_원인도_확인한다() {
        var failure = new SQLException();
        failure.setNextException(new SQLException(null, "HY000", 1205));

        assertThat(classifier.isRetryable(new TransactionSystemException(null, failure))).isTrue();
    }

    @Test
    void JDBC_추가_예외의_영구_원인도_우선한다() {
        var failure = new SQLException(null, "40001", 1213);
        failure.setNextException(new SQLIntegrityConstraintViolationException(null, "23000", 1452));

        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @Test
    void 순환하는_cause와_JDBC_연결에서_같은_예외를_반복_방문하지_않는다() {
        var first = new SQLException();
        var second = new SQLException();
        first.initCause(second);
        second.initCause(first);
        first.setNextException(second);

        assertThat(classifier.isRetryable(first)).isFalse();
    }

    @Test
    void Retry_표식은_errorCode와_원본_원인을_보존하며_분류할_수_있다() {
        var cause = new CannotAcquireLockException(null);
        var failure = new NotificationRetryableCreationException(cause);

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(classifier.isRetryable(failure)).isTrue();
    }

    @Test
    void interrupt_분류가_스레드의_중단_상태를_소비하거나_해제하지_않는다() {
        try {
            Thread.currentThread().interrupt();

            assertThat(classifier.isRetryable(new InterruptedException())).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static Stream<Throwable> retryableFailures() {
        return Stream.of(
            new CannotAcquireLockException(null),
            new QueryTimeoutException(null),
            new RecoverableDataAccessException(null),
            new SQLTransientConnectionException(),
            new SQLRecoverableException(),
            new SQLNonTransientConnectionException(null, "08001"),
            new SQLException(null, "08S01"),
            new SQLException(null, "40001"),
            new SQLException(null, "HY000", 1205),
            new SQLException(null, null, 1213),
            new DataAccessResourceFailureException(null, new SQLNonTransientConnectionException(null, "08001")),
            new CannotCreateTransactionException(null, new SQLException(null, "08S01")),
            new TransactionSystemException(null, new SQLException(null, "HY000", 1205)),
            new ExecutionException(new org.apache.kafka.common.errors.TimeoutException()),
            new NotEnoughReplicasException(),
            new TimeoutException());
    }

    private static Stream<Throwable> nonRetryableFailures() {
        return Stream.of(
            new NotificationInvalidCreateRequestException(),
            new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR),
            new DataIntegrityViolationException(null),
            new DataAccessResourceFailureException(null),
            new CannotCreateTransactionException(null),
            new SQLException(),
            new SQLException(null, "HY000"),
            new SQLException(null, "X"),
            new SQLIntegrityConstraintViolationException(null, "23000", 1062),
            new DataIntegrityViolationException(null,
                new SQLIntegrityConstraintViolationException(null, "23000", 1452)),
            new SQLInvalidAuthorizationSpecException(null, "28000", 1045),
            new SQLSyntaxErrorException(),
            new SQLException(null, "22001", 1406),
            new ExecutionException(new AuthenticationException("")),
            new AuthorizationException(""),
            new ConfigException(""),
            new SerializationException(),
            new DeserializationException(null, new byte[]{1}, false, null),
            new MessageConversionException(""),
            new IllegalStateException(),
            new InterruptedException(),
            new AssertionError());
    }

    private static Stream<Throwable> terminalCauses() {
        return Stream.of(
            new NotificationInvalidCreateRequestException(),
            new AuthenticationException(""),
            new AuthorizationException(""),
            new ConfigException(""),
            new SerializationException(new TimeoutException()),
            new DeserializationException(null, new byte[]{1}, false, new TimeoutException()),
            new MessageConversionException("", new TimeoutException()),
            new InvalidDataAccessResourceUsageException(null, new SQLTransientException()),
            new SQLIntegrityConstraintViolationException(null, "23000", 1452),
            new InterruptedException(),
            new AssertionError());
    }
}
