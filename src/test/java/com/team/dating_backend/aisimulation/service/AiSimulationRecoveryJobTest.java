package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class AiSimulationRecoveryJobTest {
    private final AiSimulationSessionRepository repository = mock(AiSimulationSessionRepository.class);
    private final AiSimulationPersistenceService persistence = mock(AiSimulationPersistenceService.class);
    private final AiSimulationRecoveryJob job = new AiSimulationRecoveryJob(repository, persistence,
        new AiSimulationProperties());

    @Test
    void 복구_조회_오류로_전체_서버_시작을_막지_않는다() {
        given(repository.findExpiredIds(eq(AiSimulationSessionStatus.IN_PROGRESS), any(), eq(PageRequest.of(0, 100))))
            .willThrow(new IllegalStateException("temporary database error"));
        assertThatCode(() -> job.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(persistence);
    }

    @Test
    void 한_세션의_복구가_실패해도_나머지를_처리한다() {
        given(repository.findExpiredIds(eq(AiSimulationSessionStatus.IN_PROGRESS), any(), eq(PageRequest.of(0, 100))))
            .willReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("temporary row error")).when(persistence).failIfExpired(eq(1L), any());
        assertThatCode(job::recoverExpired).doesNotThrowAnyException();
        verify(persistence).failIfExpired(eq(2L), any());
    }
}
