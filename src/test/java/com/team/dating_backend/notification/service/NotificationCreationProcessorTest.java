package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationDeliveryProducer;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationCreationProcessorTest {

    private static final NotificationCreateRequested REQUEST = new NotificationCreateRequested(
        1, "event-1", 7L, NotificationType.LIKE, "재처리 제목", "재처리 본문", null, null,
        Instant.parse("2026-10-21T01:00:00Z"));
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 1, 0);
    private static final NotificationEventResponse STORED = new NotificationEventResponse(
        10L, "LIKE", "원본 제목", "원본 본문", "USER", 2L, CREATED_AT.plusMinutes(1), CREATED_AT);
    private static final NotificationDeliveryEvent DELIVERY = new NotificationDeliveryEvent(1, "event-1", 7L, STORED);

    private NotificationCreationService creationService;
    private NotificationDeliveryProducer producer;
    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private NotificationCreationProcessor processor;

    @BeforeEach
    void setUp() {
        creationService = mock(NotificationCreationService.class);
        producer = mock(NotificationDeliveryProducer.class);
        dataSource = mock(DataSource.class);
        transactionManager = new DataSourceTransactionManager(dataSource);
        var target = new NotificationCreationProcessor(
            new NotificationCreateRequestedValidator(), creationService, producer);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        processor = (NotificationCreationProcessor) factory.getProxy();
    }

    @Test
    void 저장_결과를_받은_뒤_DB_transaction_없이_원본_snapshot을_발행한다() throws Exception {
        given(creationService.createOrLoad(REQUEST))
            .willReturn(new NotificationCreationResult("event-1", 7L, STORED, null));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(producer).publishAndAwait(DELIVERY);

        processor.process(REQUEST);

        var order = inOrder(creationService, producer);
        order.verify(creationService).createOrLoad(REQUEST);
        order.verify(producer).publishAndAwait(DELIVERY);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(dataSource);
    }

    @Test
    void 삭제된_기존_알림은_delivery를_발행하지_않고_정상_완료한다() throws Exception {
        given(creationService.createOrLoad(REQUEST))
            .willReturn(new NotificationCreationResult("event-1", 7L, STORED, CREATED_AT.plusMinutes(2)));

        processor.process(REQUEST);

        verifyNoInteractions(producer);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {2})
    void 잘못된_요청은_저장과_발행_전에_errorCode로_거부한다(Integer schemaVersion) {
        NotificationCreateRequested invalid = schemaVersion == null
            ? null
            : new NotificationCreateRequested(
                schemaVersion, "event-1", 7L, NotificationType.LIKE, "제목", "본문", null, null, REQUEST.requestedAt());

        assertThatThrownBy(() -> processor.process(invalid))
            .isInstanceOfSatisfying(NotificationInvalidCreateRequestException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verifyNoInteractions(creationService, producer);
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void 저장과_commit_실패는_원래_예외를_전달하고_발행하지_않는다(RuntimeException failure) {
        given(creationService.createOrLoad(REQUEST)).willThrow(failure);

        assertThatThrownBy(() -> processor.process(REQUEST)).isSameAs(failure);

        verifyNoInteractions(producer);
    }

    @ParameterizedTest
    @MethodSource("deliveryFailures")
    void 발행_실패를_성공으로_바꾸거나_자체_재시도하지_않는다(Exception failure) throws Exception {
        given(creationService.createOrLoad(REQUEST))
            .willReturn(new NotificationCreationResult("event-1", 7L, STORED, null));
        given(producer.publishAndAwait(DELIVERY)).willThrow(failure);

        assertThatThrownBy(() -> processor.process(REQUEST)).isSameAs(failure);

        var order = inOrder(creationService, producer);
        order.verify(creationService).createOrLoad(REQUEST);
        order.verify(producer).publishAndAwait(DELIVERY);
        order.verifyNoMoreInteractions();
    }

    @Test
    void 활성_transaction에서_호출하면_저장과_발행_전에_거부한다() throws Exception {
        Connection connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getAutoCommit()).willReturn(true);

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> assertThatThrownBy(() -> processor.process(REQUEST))
                .isInstanceOf(IllegalTransactionStateException.class));

        verifyNoInteractions(creationService, producer);
    }

    private static Stream<RuntimeException> storageFailures() {
        return Stream.of(new DataAccessResourceFailureException("저장 실패"), new TransactionSystemException("커밋 실패"));
    }

    private static Stream<Exception> deliveryFailures() {
        return Stream.of(new ExecutionException(new IllegalStateException()), new TimeoutException(),
            new InterruptedException(), new IllegalStateException());
    }
}
