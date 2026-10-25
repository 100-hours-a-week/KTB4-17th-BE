package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationPublishConfiguration;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.event.NotificationCreateRequestedEventListener;
import com.team.dating_backend.notification.kafka.NotificationPublishDispatcher;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {LikeSendService.class, ChatRoomCreateService.class, UserPairLockRepository.class,
        NotificationRequestService.class, NotificationCreateRequestedValidator.class,
        NotificationCreateRequestedEventListener.class, NotificationPublishDispatcher.class,
        NotificationBusinessPublishIntegrationTest.PublishConfiguration.class}
)
@Testcontainers
class NotificationBusinessPublishIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private LikeSendService service;
    @Autowired
    private LikeRepository likeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ThreadPoolTaskExecutor executor;
    @MockitoBean
    private NotificationRequestProducer producer;
    @MockitoBean
    private NotificationFailureReporter reporter;

    private TransactionTemplate transaction;
    private Long senderId;
    private Long receiverId;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        release = new CountDownLatch(1);
        senderId = saveUser();
        receiverId = saveUser();
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getQueueSize()).isZero();
        });
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications");
            jdbc.update("DELETE FROM likes");
            jdbc.update("DELETE FROM user_pair_locks");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 좋아요_commit_이후_별도_worker가_커밋된_업무를_읽고_원본_ID로_발행한다() throws Exception {
        var started = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var observed = new AtomicReference<NotificationCreateRequested>();
        doAnswer(invocation -> {
            worker.set(Thread.currentThread());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var request = (NotificationCreateRequested) invocation.getArgument(0);
            Long likeId = Long.valueOf(request.sourceEventId().substring("LIKE_SENT:".length()));
            assertThat(likeRepository.findById(likeId).orElseThrow().getStatus()).isEqualTo(LikeStatus.PENDING);
            observed.set(request);
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(producer).publishAndAwait(any());

        var response = service.sendLike(senderId, receiverId);

        assertThat(response.status()).isEqualTo(LikeStatus.PENDING);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.get()).isNotSameAs(Thread.currentThread());
        assertThat(worker.get().getName()).startsWith("notification-publish-");
        assertThat(observed.get().sourceEventId()).isEqualTo("LIKE_SENT:" + response.likeId());
        assertThat(observed.get().recipientUserId()).isEqualTo(receiverId);
        assertThat(observed.get().requestedAt()).isNotNull();
        assertThat(notificationRepository.count()).isZero();
        verifyNoInteractions(reporter);
    }

    @Test
    void 좋아요_저장_뒤_업무가_rollback되면_요청을_발행하지_않는다() {
        var failure = new NotificationInvalidCreateRequestException();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.sendLike(senderId, receiverId);
            entityManager.flush();
            assertThat(likeRepository.count()).isEqualTo(1L);
            verifyNoInteractions(producer);
            throw failure;
        })).isSameAs(failure);

        assertThat(likeRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
        verifyNoInteractions(producer, reporter);
    }

    @ParameterizedTest
    @MethodSource("publishFailures")
    void 최초_Kafka_발행이_실패해도_좋아요_응답과_업무_commit을_보존하고_실패를_보고한다(
        Exception failure, NotificationFailureCode code) throws Exception {
        doThrow(failure).when(producer).publishAndAwait(any());

        var response = service.sendLike(senderId, receiverId);

        assertThat(response.status()).isEqualTo(LikeStatus.PENDING);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
            () -> verify(reporter).report(eq(code), any(NotificationCreateRequested.class), same(failure)));
        var request = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(producer).publishAndAwait(request.capture());
        assertThat(request.getValue().sourceEventId()).isEqualTo("LIKE_SENT:" + response.likeId());
        assertThat(likeRepository.findById(response.likeId()).orElseThrow().getStatus()).isEqualTo(LikeStatus.PENDING);
        assertThat(likeRepository.count()).isEqualTo(1L);
        assertThat(notificationRepository.count()).isZero();
        verifyNoMoreInteractions(producer, reporter);
    }

    @Test
    void 실제_실행기와_대기열_포화에도_세_좋아요를_커밋하고_거절된_요청만_보고한다() throws Exception {
        var started = new CountDownLatch(1);
        List<NotificationCreateRequested> published = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(producer).publishAndAwait(any());

        var first = service.sendLike(senderId, receiverId);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var second = service.sendLike(senderId, saveUser());
        Long rejectedRecipient = saveUser();
        var third = service.sendLike(senderId, rejectedRecipient);

        var rejectedRequest = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(reporter).report(eq(NotificationFailureCode.PUBLISH_SUBMISSION_REJECTED), rejectedRequest.capture(),
            any(RejectedExecutionException.class));
        assertThat(rejectedRequest.getValue().sourceEventId()).isEqualTo("LIKE_SENT:" + third.likeId());
        assertThat(rejectedRequest.getValue().recipientUserId()).isEqualTo(rejectedRecipient);
        assertThat(likeRepository.findAll()).hasSize(3)
            .allSatisfy(like -> assertThat(like.getStatus()).isEqualTo(LikeStatus.PENDING));
        assertThat(executor.getQueueSize()).isEqualTo(1);
        assertThat(published).extracting(NotificationCreateRequested::sourceEventId)
            .containsExactly("LIKE_SENT:" + first.likeId());

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(published).extracting(NotificationCreateRequested::sourceEventId)
                .containsExactly("LIKE_SENT:" + first.likeId(), "LIKE_SENT:" + second.likeId());
            assertThat(executor.getActiveCount()).isZero();
        });
        verify(producer, times(2)).publishAndAwait(any());
        verifyNoMoreInteractions(reporter);
        assertThat(notificationRepository.count()).isZero();
    }

    private Long saveUser() {
        return transaction.execute(status -> {
            var now = LocalDateTime.now();
            var user = User.create("회원", LocalDate.of(2000, 1, 1), Gender.FEMALE, now);
            user.activate(now);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private static Stream<Arguments> publishFailures() {
        return Stream.of(
            Arguments.of(new TimeoutException(), NotificationFailureCode.PUBLISH_RESULT_TIMEOUT),
            Arguments.of(new ExecutionException(new IllegalStateException()),
                NotificationFailureCode.PUBLISH_RESULT_FAILED),
            Arguments.of(new IllegalStateException(), NotificationFailureCode.PUBLISH_CALL_FAILED));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishConfiguration {

        @Bean("notificationPublishExecutor")
        ThreadPoolTaskExecutor notificationPublishExecutor(ApplicationContext context) {
            var properties = new NotificationPublishProperties();
            properties.setPoolSize(1);
            properties.setQueueCapacity(1);
            properties.setShutdownWaitSeconds(5);
            return new NotificationPublishConfiguration().notificationPublishExecutor(properties, context);
        }
    }
}
