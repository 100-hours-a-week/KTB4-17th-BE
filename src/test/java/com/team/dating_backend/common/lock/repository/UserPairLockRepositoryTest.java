package com.team.dating_backend.common.lock.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class UserPairLockRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private UserPairLockRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new UserPairLockRepository(jdbcTemplate);
    }

    @ParameterizedTest
    @CsvSource({"7, 8", "8, 7"})
    void 요청_방향과_무관하게_같은_순서로_PAIR를_잠근다(
        Long requesterUserId, Long targetUserId) {
        repository.acquire(requesterUserId, targetUserId);

        verify(jdbcTemplate).update(
            anyString(), eq(7L), eq(8L), any(LocalDateTime.class));
    }

    @Test
    void 같은_사용자_PAIR는_SQL을_실행하지_않는다() {
        assertThatThrownBy(() -> repository.acquire(7L, 7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("User pair IDs must be different");

        verifyNoInteractions(jdbcTemplate);
    }
}
