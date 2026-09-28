package com.team.dating_backend.aisimulation.repository;

import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSimulationReportRepository
    extends
        JpaRepository<AiSimulationReport, Long> {

    Optional<AiSimulationReport> findBySessionId(Long sessionId);

    Optional<AiSimulationReport> findBySessionIdAndDeletedAtIsNull(Long sessionId);
}
