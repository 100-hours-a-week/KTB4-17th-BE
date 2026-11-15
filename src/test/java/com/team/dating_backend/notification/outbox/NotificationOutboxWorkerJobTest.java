package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationOutboxWorkerProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationOutboxWorkerJobTest {

    private final NotificationOutboxClaimService claims = mock(NotificationOutboxClaimService.class);
    private final NotificationOutboxWorker worker = mock(NotificationOutboxWorker.class);
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final NotificationOutboxWorkerProperties properties = new NotificationOutboxWorkerProperties();
    private final NotificationOutboxWorkerJob job = new NotificationOutboxWorkerJob(claims, worker, properties,
        reporter);
    private final NotificationRequestOutbox first = mock(NotificationRequestOutbox.class);
    private final NotificationRequestOutbox second = mock(NotificationRequestOutbox.class);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void 한_건씩_점유하고_처리한_뒤_다음_건을_가져오며_없으면_종료한다() throws Exception {
        given(claims.tryClaimNext()).willReturn(Optional.of(first), Optional.of(second), Optional.empty());

        job.processDueRequests();

        var order = inOrder(claims, worker);
        order.verify(claims).tryClaimNext();
        order.verify(worker).process(first);
        order.verify(claims).tryClaimNext();
        order.verify(worker).process(second);
        order.verify(claims).tryClaimNext();
        order.verifyNoMoreInteractions();
        verifyNoInteractions(reporter);
    }

    @Test
    void 기본_한도는_100건이며_다음_실행에서_다시_처리할_수_있다() throws Exception {
        given(claims.tryClaimNext()).willReturn(Optional.of(first));

        job.processDueRequests();
        verify(claims, times(100)).tryClaimNext();
        verify(worker, times(100)).process(first);

        properties.setMaxRequestsPerRun(2);
        job.processDueRequests();
        verify(claims, times(102)).tryClaimNext();
        verify(worker, times(102)).process(first);
    }

    @Test
    void 비활성화와_이미_중단된_실행은_점유하지_않는다() {
        properties.setEnabled(false);
        job.processDueRequests();
        properties.setEnabled(true);
        Thread.currentThread().interrupt();
        job.processDueRequests();

        verifyNoInteractions(claims, worker, reporter);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 업무_트랜잭션에서는_점유를_시작하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(job::processDueRequests).isInstanceOf(NotificationBusinessException.class);
        verifyNoInteractions(claims, worker, reporter);
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void Worker가_처리한_실패는_중복_보고하지_않고_다음_건을_처리한다(Exception failure) throws Exception {
        given(claims.tryClaimNext()).willReturn(Optional.of(first), Optional.of(second), Optional.empty());
        doThrow(failure).when(worker).process(first);

        job.processDueRequests();

        verify(worker).process(second);
        verifyNoInteractions(reporter);
    }

    @Test
    void 중단_예외는_표시를_복원하고_추가_점유를_막는다() throws Exception {
        given(claims.tryClaimNext()).willReturn(Optional.of(first));
        doThrow(new InterruptedException()).when(worker).process(first);

        job.processDueRequests();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(claims).tryClaimNext();
        verifyNoInteractions(reporter);
    }

    @Test
    void 점유_직후_중단되어도_점유한_행은_Worker의_해제_흐름에_전달한다() throws Exception {
        given(claims.tryClaimNext()).willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return Optional.of(first);
        });
        doThrow(new InterruptedException()).when(worker).process(first);

        job.processDueRequests();

        verify(worker).process(first);
        verify(claims).tryClaimNext();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 처리_후_비활성화되거나_중단_표시가_남으면_추가_점유하지_않는다() throws Exception {
        given(claims.tryClaimNext()).willReturn(Optional.of(first));
        doAnswer(invocation -> {
            properties.setEnabled(false);
            return null;
        }).when(worker).process(first);
        job.processDueRequests();
        properties.setEnabled(true);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new ExecutionException(new InterruptedException());
        }).when(worker).process(first);
        job.processDueRequests();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(claims, times(2)).tryClaimNext();
        verifyNoInteractions(reporter);
    }

    @Test
    void 점유_DB_오류와_보고_실패는_현재_실행만_종료하고_다음_실행을_허용한다() throws Exception {
        var failure = new DataAccessResourceFailureException("점유 실패");
        var reporting = new IllegalStateException("보고 실패");
        given(claims.tryClaimNext()).willThrow(failure).willReturn(Optional.empty());
        doThrow(reporting).when(reporter).report(any(), eq(false));

        job.processDueRequests();
        job.processDueRequests();

        verify(claims, times(2)).tryClaimNext();
        verifyNoInteractions(worker);
        verify(reporter).report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_CLAIM_FAILED
            && context.sourceEventId() == null), eq(false));
        assertThat(failure.getSuppressed()).containsExactly(reporting);
    }

    @Test
    void 동일_Job의_동시_진입은_점유를_추가하지_않는다() throws Exception {
        properties.setMaxRequestsPerRun(1);
        given(claims.tryClaimNext()).willReturn(Optional.of(first));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(worker).process(first);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var running = executor.submit(job::processDueRequests);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                job.processDueRequests();
                verify(claims).tryClaimNext();
                finish.countDown();
                running.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
    }

    private static Stream<Exception> processingFailures() {
        return Stream.of(new TimeoutException(), new ExecutionException(new IllegalStateException()),
            new IllegalArgumentException());
    }
}
