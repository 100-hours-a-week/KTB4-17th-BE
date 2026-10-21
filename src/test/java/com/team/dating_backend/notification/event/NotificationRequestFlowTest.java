package com.team.dating_backend.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.kafka.NotificationPublishDispatcher;
import com.team.dating_backend.notification.service.NotificationRequestService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationRequestFlowTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-20T01:02:03.123456789Z");
    private final Connection connection = mock(Connection.class);
    private final NotificationPublishDispatcher dispatcher = mock(NotificationPublishDispatcher.class);
    private final List<NotificationCreateRequested> publishedRequests = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private NotificationRequestService service;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = mock(DataSource.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.getAutoCommit()).willReturn(true);
        var manager = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof NotificationCreateRequested request) {
                publishedRequests.add(request);
            }
        });
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(NotificationPublishDispatcher.class, () -> dispatcher);
        context.register(TransactionConfiguration.class, NotificationRequestService.class,
            NotificationCreateRequestedEventListener.class);
        context.refresh();
        service = context.getBean(NotificationRequestService.class);
        transaction = new TransactionTemplate(manager);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void 업무_commit_이후에_원본_요청과_나노초_시각을_그대로_한번_전달한다() throws SQLException {
        var request = request("title", "body", null, null);

        transaction.executeWithoutResult(status -> {
            service.requestNotification(request);
            verifyNoInteractions(dispatcher);
        });

        var order = inOrder(connection, dispatcher);
        order.verify(connection).commit();
        order.verify(dispatcher).dispatch(same(request));
        assertThat(request.requestedAt()).isSameAs(REQUESTED_AT);
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    void transaction_없이_호출하면_조용히_누락시키지_않고_거부한다() {
        assertThatThrownBy(() -> service.requestNotification(request("title", "body", null, null)))
            .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(connection, dispatcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rollback-only", "business-exception", "commit-failure"})
    void 업무_rollback이나_commit_실패이면_전송_작업을_제출하지_않는다(String failure) throws SQLException {
        var request = request("title", "body", null, null);
        switch (failure) {
            case "rollback-only" -> transaction.executeWithoutResult(status -> {
                service.requestNotification(request);
                status.setRollbackOnly();
            });
            case "business-exception" -> assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                service.requestNotification(request);
                throw new IllegalStateException();
            })).isInstanceOf(IllegalStateException.class);
            case "commit-failure" -> {
                doThrow(new SQLException()).when(connection).commit();
                assertThatThrownBy(
                    () -> transaction.executeWithoutResult(status -> service.requestNotification(request)))
                    .isInstanceOf(TransactionSystemException.class);
            }
        }

        verifyNoInteractions(dispatcher);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 잘못된_요청은_errorCode로_거부하고_이벤트를_등록하지_않는다(NotificationCreateRequested request) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.requestNotification(request)))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        assertThat(publishedRequests).isEmpty();
        verifyNoInteractions(dispatcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void 원본_식별자_255자를_보존해_commit_이후_전달한다(String character) {
        String prefix = "Like_SENT:501 ";
        String sourceEventId = prefix + character.repeat(255 - prefix.length());
        var request = new NotificationCreateRequested(1, sourceEventId, 7L, NotificationType.LIKE,
            "title", "body", null, null, REQUESTED_AT);

        transaction.executeWithoutResult(status -> {
            service.requestNotification(request);
            assertThat(publishedRequests).containsExactly(request);
            verifyNoInteractions(dispatcher);
        });

        verify(dispatcher).dispatch(same(request));
        assertThat(request.sourceEventId()).isEqualTo(sourceEventId);
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    void 기존_알림의_유니코드_길이_경계와_빈_문자열_허용을_유지한다() {
        var boundary = request("😀".repeat(100), "😀".repeat(500), "😀".repeat(50), 1L);
        var blank = request("", " ", null, null);

        transaction.executeWithoutResult(status -> {
            service.requestNotification(boundary);
            service.requestNotification(blank);
        });

        verify(dispatcher).dispatch(same(boundary));
        verify(dispatcher).dispatch(same(blank));
        verifyNoMoreInteractions(dispatcher);
    }

    private static Stream<NotificationCreateRequested> invalidRequests() {
        return Stream.of(null,
            new NotificationCreateRequested(2, "id", 7L, NotificationType.LIKE, "title", "body", null, null,
                REQUESTED_AT),
            new NotificationCreateRequested(1, " ", 7L, NotificationType.LIKE, "title", "body", null, null,
                REQUESTED_AT),
            new NotificationCreateRequested(1, "a".repeat(256), 7L, NotificationType.LIKE, "title", "body", null, null,
                REQUESTED_AT),
            new NotificationCreateRequested(1, "😀".repeat(256), 7L, NotificationType.LIKE, "title", "body", null, null,
                REQUESTED_AT),
            new NotificationCreateRequested(1, "id", 0L, NotificationType.LIKE, "title", "body", null, null,
                REQUESTED_AT),
            new NotificationCreateRequested(1, "id", 7L, null, "title", "body", null, null, REQUESTED_AT),
            new NotificationCreateRequested(1, "id", 7L, NotificationType.LIKE, "title", "body", null, null, null),
            request(null, "body", null, null), request("title", null, null, null),
            request("😀".repeat(101), "body", null, null), request("title", "😀".repeat(501), null, null),
            request("title", "body", "CHAT", null), request("title", "body", null, 1L),
            request("title", "body", " ", 1L), request("title", "body", "CHAT", 0L),
            request("title", "body", "😀".repeat(51), 1L));
    }

    private static NotificationCreateRequested request(String title, String body, String targetType, Long targetId) {
        return new NotificationCreateRequested(1, "LIKE_SENT:501", 7L, NotificationType.LIKE,
            title, body, targetType, targetId, REQUESTED_AT);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {}
}
