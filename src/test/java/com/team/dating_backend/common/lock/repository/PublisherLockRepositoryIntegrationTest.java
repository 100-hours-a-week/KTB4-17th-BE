package com.team.dating_backend.common.lock.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.lock.entity.PublisherLock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublisherLockRepositoryIntegrationTest {

    private static final String LOCK_NAME = "chat-message-outbox-publisher";
    private static final String FIRST_OWNER = "chat-server-1";
    private static final String SECOND_OWNER = "chat-server-2";
    private static final long LOCK_TTL_MICROS = TimeUnit.SECONDS.toMicros(30);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private PublisherLockRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetLock() {
        jdbcTemplate.update(
            """
                UPDATE publisher_locks
                SET owner_id = NULL,
                    locked_until = '1970-01-01 00:00:00.000000',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE lock_name = ?
                """,
            LOCK_NAME);
    }

    @Test
    void 락을_획득한_서버만_락을_연장할_수_있다() {
        assertThat(repository.tryAcquireOrExtend(
            LOCK_NAME, FIRST_OWNER, LOCK_TTL_MICROS)).isEqualTo(1);
        PublisherLock acquired = lock();

        assertThat(repository.tryAcquireOrExtend(
            LOCK_NAME, SECOND_OWNER, LOCK_TTL_MICROS)).isZero();
        assertThat(repository.tryAcquireOrExtend(
            LOCK_NAME, FIRST_OWNER, LOCK_TTL_MICROS)).isEqualTo(1);
        PublisherLock extended = lock();

        assertThat(acquired.getOwnerId()).isEqualTo(FIRST_OWNER);
        assertThat(extended.getOwnerId()).isEqualTo(FIRST_OWNER);
        assertThat(extended.getLockedUntil()).isAfterOrEqualTo(acquired.getLockedUntil());
        assertThat(extended.getUpdatedAt()).isAfterOrEqualTo(acquired.getUpdatedAt());
    }

    @Test
    void 락이_만료되면_다른_서버가_획득할_수_있다() {
        repository.tryAcquireOrExtend(LOCK_NAME, FIRST_OWNER, LOCK_TTL_MICROS);
        expireLock();

        assertThat(repository.tryAcquireOrExtend(
            LOCK_NAME, SECOND_OWNER, LOCK_TTL_MICROS)).isEqualTo(1);

        PublisherLock lock = lock();
        assertThat(lock.getOwnerId()).isEqualTo(SECOND_OWNER);
        assertThat(lock.getLockedUntil()).isAfter(databaseNow());
    }

    @Test
    void 두_서버가_동시에_락을_요청하면_하나만_획득한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> acquireAfter(start, FIRST_OWNER));
            var second = executor.submit(() -> acquireAfter(start, SECOND_OWNER));

            start.countDown();

            assertThat(List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(0, 1);
        }

        assertThat(lock().getOwnerId()).isIn(FIRST_OWNER, SECOND_OWNER);
    }

    private int acquireAfter(CountDownLatch start, String ownerId) throws InterruptedException {
        start.await();
        return repository.tryAcquireOrExtend(LOCK_NAME, ownerId, LOCK_TTL_MICROS);
    }

    private PublisherLock lock() {
        return repository.findById(LOCK_NAME).orElseThrow();
    }

    private void expireLock() {
        jdbcTemplate.update(
            """
                UPDATE publisher_locks
                SET locked_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE lock_name = ?
                """,
            LOCK_NAME);
    }

    private LocalDateTime databaseNow() {
        return jdbcTemplate.queryForObject(
            "SELECT CURRENT_TIMESTAMP(6)",
            (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
    }
}
