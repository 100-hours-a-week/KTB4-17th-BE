package com.team.dating_backend.profiletrust.repository;

import com.team.dating_backend.profiletrust.entity.FaceVerificationAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceVerificationAttemptRepository extends JpaRepository<FaceVerificationAttempt, Long> {

    Optional<FaceVerificationAttempt> findBySessionIdAndAttemptNo(Long sessionId, int attemptNo);

    Optional<FaceVerificationAttempt> findFirstBySessionIdOrderByAttemptNoDesc(Long sessionId);

    boolean existsBySessionIdAndVideoHash(Long sessionId, String videoHash);
}
