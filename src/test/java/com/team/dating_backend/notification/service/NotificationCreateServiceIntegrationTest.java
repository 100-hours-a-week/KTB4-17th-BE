package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.dto.event.NotificationCreatedEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;
import com.team.dating_backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NotificationCreateService.class, NotificationCreateServiceIntegrationTest.EventRecorder.class})
@Testcontainers
class NotificationCreateServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationCreateService service;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventRecorder events;

    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        events.reset();
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(status -> {
            User user = User.create("테스트", LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
            user.activate(NOW);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
        events.reset();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 알림을_저장하고_동일한_데이터의_이벤트를_커밋_이후에_처리한다(boolean hasTarget) {
        String targetType = hasTarget ? "CHAT_ROOM" : null;
        Long targetId = hasTarget ? 999999L : null;

        NotificationCreatedEvent published = transaction.execute(status -> {
            service.createNotification(userId, NotificationType.MATCH,
                "새로운 매칭", "매칭이 성사되었어요.", targetType, targetId);

            assertThat(events.published).hasSize(1);
            assertThat(events.committed).isEmpty();
            return events.published.getFirst();
        });

        List<Notification> notifications = repository.findVisibleByUserId(
            userId, PageRequest.of(0, 20));
        assertThat(notifications).hasSize(1);
        Notification stored = notifications.getFirst();
        assertThat(stored.getUserId()).isEqualTo(userId);
        assertThat(stored.getType()).isEqualTo(NotificationType.MATCH);
        assertThat(stored.getTitle()).isEqualTo("새로운 매칭");
        assertThat(stored.getBody()).isEqualTo("매칭이 성사되었어요.");
        assertThat(stored.getTargetType()).isEqualTo(targetType);
        assertThat(stored.getTargetId()).isEqualTo(targetId);
        assertThat(stored.getReadAt()).isNull();
        assertThat(stored.getDeletedAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(published).isEqualTo(new NotificationCreatedEvent(
            userId, NotificationEventResponse.from(stored)));
        assertThat(events.committed).containsExactly(published);
        assertThat(repository.countVisibleUnreadByUserId(userId)).isEqualTo(1L);
    }

    @Test
    void 트랜잭션_없이_호출하면_알림_저장과_이벤트_발행_전에_실패한다() {
        assertThatThrownBy(this::createNotification)
            .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(repository.countVisibleUnreadByUserId(userId)).isZero();
        assertThat(events.published).isEmpty();
        assertThat(events.committed).isEmpty();
    }

    @Test
    void 호출한_업무가_실패하면_업무_변경과_알림을_함께_롤백하고_커밋_이후_처리를_제외한다() {
        IllegalStateException failure = new IllegalStateException("업무 처리 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            confirmPersonaOnboarding();
            createNotification();
            entityManager.flush();
            assertThat(events.published).hasSize(1);
            assertThat(events.committed).isEmpty();
            throw failure;
        })).isSameAs(failure);

        assertRolledBack();
        assertThat(events.published).hasSize(1);
    }

    @Test
    void 동기_이벤트_처리_실패는_호출한_업무_변경과_알림을_함께_롤백한다() {
        IllegalStateException failure = new IllegalStateException("동기 이벤트 처리 실패");
        events.failure = failure;

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            confirmPersonaOnboarding();
            createNotification();
        })).isSameAs(failure);

        assertRolledBack();
        assertThat(events.published).hasSize(1);
    }

    @Test
    void 두번째_알림의_수신_회원이_존재하지_않으면_앞서_저장한_알림까지_롤백한다() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            confirmPersonaOnboarding();
            createNotification();
            service.createNotification(Long.MAX_VALUE, NotificationType.MATCH,
                "새로운 매칭", "매칭이 성사되었어요.", null, null);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertRolledBack();
        assertThat(events.published).hasSize(1);
    }

    @Test
    void 같은_회원과_유형과_이동_대상의_알림도_별도_ID로_각각_저장한다() {
        transaction.executeWithoutResult(status -> {
            createNotification();
            createNotification();
            assertThat(events.committed).isEmpty();
        });

        List<Notification> notifications = repository.findVisibleByUserId(
            userId, PageRequest.of(0, 20));
        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(Notification::getId).doesNotHaveDuplicates();
        assertThat(events.published).hasSize(2);
        assertThat(events.committed).containsExactlyElementsOf(events.published);
        assertThat(notifications).extracting(Notification::getId)
            .containsExactlyInAnyOrderElementsOf(events.committed.stream()
                .map(event -> event.notification().notificationId()).toList());
        assertThat(repository.countVisibleUnreadByUserId(userId)).isEqualTo(2L);
    }

    private void createNotification() {
        service.createNotification(userId, NotificationType.MATCH,
            "새로운 매칭", "매칭이 성사되었어요.", "CHAT_ROOM", 999999L);
    }

    private void confirmPersonaOnboarding() {
        userRepository.findById(userId).orElseThrow().confirmPersonaOnboarding(NOW.plusMinutes(1));
    }

    private void assertRolledBack() {
        assertThat(repository.countVisibleUnreadByUserId(userId)).isZero();
        assertThat(userRepository.findById(userId).orElseThrow().getPersonaOnboardingStatus())
            .isEqualTo(PersonaOnboardingStatus.PENDING);
        assertThat(events.committed).isEmpty();
    }

    static class EventRecorder {

        private final List<NotificationCreatedEvent> published = new ArrayList<>();
        private final List<NotificationCreatedEvent> committed = new ArrayList<>();
        private RuntimeException failure;

        @EventListener
        public void onPublished(NotificationCreatedEvent event) {
            published.add(event);
            if (failure != null) {
                throw failure;
            }
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCommitted(NotificationCreatedEvent event) {
            committed.add(event);
        }

        private void reset() {
            published.clear();
            committed.clear();
            failure = null;
        }
    }
}
