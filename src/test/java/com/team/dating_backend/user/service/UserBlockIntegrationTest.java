package com.team.dating_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
    }
)
@Import(UserBlockService.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserBlockIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private UserBlockService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private Long blockerId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        userBlockRepository.deleteAll();
        userRepository.deleteAll();
        blockerId = activeUser("차단회원").getId();
        targetId = activeUser("대상회원").getId();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 동일_방향_차단을_동시에_요청해도_DB에_한_행만_생성하고_같은_시각을_반환한다()
        throws Exception {
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<UserBlockResult>> requests = java.util.stream.IntStream.range(0, 3)
            .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(start);
                return service.block(blockerId, targetId);
            }, executor))
            .toList();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<UserBlockResult> results = requests.stream()
            .map(request -> request.orTimeout(10, TimeUnit.SECONDS).join())
            .toList();

        assertThat(results).extracting(UserBlockResult::created)
            .containsExactlyInAnyOrder(true, false, false);
        assertThat(results).extracting(result -> result.response().blockedAt())
            .containsOnly(results.getFirst().response().blockedAt());
        assertThat(userBlockRepository.count()).isEqualTo(1);
    }

    private User activeUser(String name) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 10, 0);
        User user = User.create(name, LocalDate.of(1995, 1, 1), Gender.MALE, now);
        user.activate(now);
        return userRepository.saveAndFlush(user);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent block start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent block interrupted", exception);
        }
    }
}
