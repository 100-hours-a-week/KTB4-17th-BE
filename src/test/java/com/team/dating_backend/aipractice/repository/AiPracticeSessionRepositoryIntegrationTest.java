package com.team.dating_backend.aipractice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class AiPracticeSessionRepositoryIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiPracticeSessionRepository sessionRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private EntityManager entityManager;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        userBlockRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionId = sessionRepository.save(new AiPracticeSession(
            USER_ID, TARGET_USER_ID, LocalDateTime.now())).getId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 어느_방향이든_활성_차단이_있으면_세션에_직접_접근할_수_없다(
        boolean requesterBlocksTarget) {
        userBlockRepository.save(new UserBlock(
            requesterBlocksTarget ? USER_ID : TARGET_USER_ID,
            requesterBlocksTarget ? TARGET_USER_ID : USER_ID,
            LocalDateTime.now()));
        entityManager.flush();
        entityManager.clear();

        assertThat(sessionRepository.findAccessibleByIdAndUserId(
            sessionId, USER_ID, UserStatus.WITHDRAWN))
            .isEmpty();
        assertThat(sessionRepository.findAccessibleByIdAndUserIdForUpdate(
            sessionId, USER_ID, UserStatus.WITHDRAWN))
            .isEmpty();
    }
}
