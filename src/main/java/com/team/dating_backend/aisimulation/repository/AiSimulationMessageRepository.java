package com.team.dating_backend.aisimulation.repository;

import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSimulationMessageRepository
    extends
        JpaRepository<AiSimulationMessage, Long> {

    List<AiSimulationMessage> findBySessionIdOrderByIdAsc(Long sessionId);
}
