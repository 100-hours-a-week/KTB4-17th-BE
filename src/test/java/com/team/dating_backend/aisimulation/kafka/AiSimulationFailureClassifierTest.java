package com.team.dating_backend.aisimulation.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class AiSimulationFailureClassifierTest {

    private final AiSimulationFailureClassifier classifier = new AiSimulationFailureClassifier();

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void 일시_장애는_업무와_프레임워크_예외에_감싸져도_재시도한다(Throwable cause) {
        for (Throwable failure : List.of(cause, new ExecutionException(cause),
            new TransactionSystemException(null, cause),
            new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED, cause))) {
            assertThat(classifier.isRetryable(failure)).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("terminalFailures")
    void 영구_원인은_바깥_예외의_일시_장애보다_우선한다(Throwable cause) {
        assertThat(classifier.isRetryable(cause)).isFalse();
        assertThat(classifier.isRetryable(new org.apache.kafka.common.errors.TimeoutException(cause))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("unknownFailures")
    void 원인을_확인하지_못하는_실패는_임의로_재시도하지_않는다(Throwable failure) {
        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AiSimulationErrorCode.class)
    void 업무_에러코드는_명시한_AI_일시_오류만_원인없이_재시도한다(AiSimulationErrorCode code) {
        var failure = new AiSimulationBusinessException(code);
        boolean expected = Set.of(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE,
            AiSimulationErrorCode.SIMULATION_GENERATION_FAILED,
            AiSimulationErrorCode.SIMULATION_ALREADY_RUNNING).contains(code);

        assertThat(classifier.isRetryable(failure)).isEqualTo(expected);
        assertThat(failure.getErrorCode()).isEqualTo(code);
        assertThat(failure.getCause()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 502, 503, 504})
    void AI_HTTP_일시_오류는_변환된_에러코드보다_실제_응답을_확인한다(int status) {
        var response = response(status);
        assertThat(classifier.isRetryable(response)).isTrue();
        assertThat(classifier.isRetryable(new AiSimulationBusinessException(
            AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID, response))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 410, 422, 501, 505})
    void AI_HTTP_요청과_기능_오류는_일시_장애로_감싸져도_재시도하지_않는다(int status) {
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE, response(status));
        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0A000", "22001", "23000", "28000", "3D000", "3F000", "42000"})
    void 영구_SQLState는_일시_DB_예외보다_우선한다(String state) {
        assertThat(classifier.isRetryable(new QueryTimeoutException(null, new SQLException(null, state)))).isFalse();
    }

    @Test
    void JDBC_추가_예외에서_일시_원인을_찾고_영구_원인이_함께_있으면_재시도하지_않는다() {
        var failure = new SQLException();
        var transientSql = new SQLException(null, "HY000", 1205);
        failure.setNextException(transientSql);
        assertThat(classifier.isRetryable(failure)).isTrue();

        transientSql.setNextException(new SQLIntegrityConstraintViolationException(null, "23000", 1062));
        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @Test
    void cause와_JDBC_추가_예외가_순환해도_분류를_종료한다() {
        var first = new SQLException();
        var second = new SQLException();
        first.initCause(second);
        second.initCause(first);
        first.setNextException(second);

        assertThat(classifier.isRetryable(first)).isFalse();
    }

    @Test
    void 응답_변환과_검증_실패는_안쪽에_Timeout이_있어도_재시도하지_않는다() {
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID,
            new TimeoutException());
        assertThat(classifier.isRetryable(failure)).isFalse();
    }

    @Test
    void Retry_표식도_원인을_검사하고_원래_예외와_에러코드를_변경하지_않는다() {
        var cause = new CannotAcquireLockException(null);
        var failure = new AiSimulationRetryableProcessingException(cause);

        assertThat(classifier.isRetryable(failure)).isTrue();
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(failure.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED);
        assertThat(failure.getMessage()).isNull();
        assertThat(classifier.isRetryable(new AiSimulationRetryableProcessingException(
            new SerializationException(new TimeoutException())))).isFalse();
    }

    @Test
    void 분류_과정에서_스레드의_interrupt_상태를_소비하지_않는다() {
        try {
            Thread.currentThread().interrupt();
            assertThat(classifier.isRetryable(new InterruptedException())).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static RestClientResponseException response(int status) {
        return new RestClientResponseException("", HttpStatusCode.valueOf(status), "", HttpHeaders.EMPTY,
            new byte[0], null);
    }

    private static Stream<Throwable> retryableFailures() {
        return Stream.of(
            new CannotAcquireLockException(null), new QueryTimeoutException(null),
            new RecoverableDataAccessException(null), new SQLTransientException(),
            new SQLRecoverableException(), new SQLNonTransientConnectionException(null, "08001"),
            new SQLException(null, "08S01"), new SQLException(null, "40001"),
            new SQLException(null, "HY000", 1205), new SQLException(null, null, 1213),
            new CannotCreateTransactionException(null, new SQLException(null, "08S01")),
            new NotEnoughReplicasException(), new org.apache.kafka.common.errors.TimeoutException(),
            new TimeoutException(), new ResourceAccessException("", new SocketTimeoutException()));
    }

    private static Stream<Throwable> terminalFailures() {
        return Stream.of(
            new InterruptedException(), new AssertionError(), new IllegalArgumentException(new TimeoutException()),
            new AuthenticationException(""), new AuthorizationException(""), new ConfigException(""),
            new SerializationException(new TimeoutException()),
            new DeserializationException(null, new byte[]{1}, false, new TimeoutException()),
            new MessageConversionException("", new TimeoutException()),
            new DataIntegrityViolationException(null, new SQLTransientException()),
            new InvalidDataAccessResourceUsageException(null, new SQLTransientException()),
            new SQLIntegrityConstraintViolationException(null, "23000", 1062),
            new SQLInvalidAuthorizationSpecException(null, "28000", 1045), new SQLSyntaxErrorException(),
            new ResourceAccessException("", new SSLHandshakeException("")),
            new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_NOT_CONFIGURED, new TimeoutException()),
            new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED, new TimeoutException()));
    }

    private static Stream<Throwable> unknownFailures() {
        return Stream.of(new IllegalStateException(), new IOException(), new SQLException(),
            new AiSimulationBusinessException(null),
            new SQLException(null, "HY000"), new SQLException(null, "X"),
            new DataAccessResourceFailureException(null), new CannotCreateTransactionException(null));
    }
}
