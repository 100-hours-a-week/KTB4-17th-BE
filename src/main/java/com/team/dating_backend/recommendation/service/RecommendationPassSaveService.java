package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.recommendation.dto.response.RecommendationPassSaveResponse;
import com.team.dating_backend.recommendation.entity.RecommendationPass;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationBusinessException;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.repository.RecommendationPassRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecommendationPassSaveService {

    private static final String PASS_DIRECTION_UNIQUE_CONSTRAINT = "uk_recommendation_pass_direction";

    private final UserRepository userRepository;
    private final RecommendationPassRepository recommendationPassRepository;
    private final RecommendationItemRepository recommendationItemRepository;
    private final TransactionTemplate transactionTemplate;

    public RecommendationPassSaveService(UserRepository userRepository,
        RecommendationPassRepository recommendationPassRepository,
        RecommendationItemRepository recommendationItemRepository,
        PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.recommendationPassRepository = recommendationPassRepository;
        this.recommendationItemRepository = recommendationItemRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RecommendationPassSaveResult savePass(Long requesterUserId, Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0) {
            throw new RequestValidationException();
        }
        if (requesterUserId.equals(targetUserId)) {
            throw new RecommendationBusinessException(RecommendationErrorCode.SELF_PASS_NOT_ALLOWED);
        }

        try {
            return transactionTemplate.execute(status -> saveInTransaction(requesterUserId, targetUserId));
        } catch (DataIntegrityViolationException exception) {
            if (!isPassDirectionUniqueViolation(exception)) {
                throw exception;
            }
            return transactionTemplate.execute(status -> recommendationPassRepository
                .findByPasserUserIdAndPassedUserId(requesterUserId, targetUserId)
                .map(existing -> result(existing, false))
                .orElseThrow(() -> exception));
        }
    }

    private RecommendationPassSaveResult saveInTransaction(Long requesterUserId, Long targetUserId) {
        Optional<User> requester = userRepository.findById(requesterUserId);
        if (requester.isEmpty() || requester.get().getStatus() != UserStatus.ACTIVE) {
            throw new RecommendationBusinessException(RecommendationErrorCode.REQUESTER_NOT_ACTIVE);
        }

        Optional<RecommendationPass> existing = recommendationPassRepository
            .findByPasserUserIdAndPassedUserId(requesterUserId, targetUserId);
        if (existing.isPresent()) {
            return result(existing.get(), false);
        }
        if (!recommendationItemRepository.existsEligibleCandidateInActiveBatch(
            requesterUserId, targetUserId)) {
            throw new RecommendationBusinessException(
                RecommendationErrorCode.RECOMMENDATION_TARGET_NOT_AVAILABLE);
        }

        RecommendationPass recommendationPass = recommendationPassRepository.saveAndFlush(
            new RecommendationPass(requesterUserId, targetUserId,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)));
        return result(recommendationPass, true);
    }

    private boolean isPassDirectionUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                if (PASS_DIRECTION_UNIQUE_CONSTRAINT.equals(constraintName)
                    || (constraintName != null
                        && constraintName.endsWith("." + PASS_DIRECTION_UNIQUE_CONSTRAINT))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private RecommendationPassSaveResult result(RecommendationPass recommendationPass,
        boolean created) {
        return new RecommendationPassSaveResult(
            new RecommendationPassSaveResponse(recommendationPass.getPassedUserId(),
                recommendationPass.getCreatedAt(), true),
            created);
    }
}
