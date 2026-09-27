package com.team.dating_backend.aisimulation.repository;

import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSimulationSessionRepository
    extends
        JpaRepository<AiSimulationSession, Long> {

    List<AiSimulationSession> findByUserIdAndStatusOrderByIdDesc(
        Long userId,
        AiSimulationSessionStatus status,
        Pageable pageable);

    Optional<AiSimulationSession> findByIdAndUserIdAndStatus(
        Long id,
        Long userId,
        AiSimulationSessionStatus status);
}
