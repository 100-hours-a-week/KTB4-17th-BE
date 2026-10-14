package com.team.dating_backend.matching.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeRejectService {

    private final UserRepository userRepository;
    private final LikeRepository likeRepository;

    @Transactional
    public void rejectLike(Long receiverId, Long likeId) {
        validateLikeId(likeId);
        userRepository.findByIdForUpdate(receiverId)
            .filter(user -> user.getStatus() != UserStatus.WITHDRAWN)
            .orElseThrow(AuthenticationRequiredException::new);
        Like like = likeRepository.findById(likeId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.LIKE_NOT_FOUND));
        if (!like.getReceiverId().equals(receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.NOT_LIKE_RECEIVER);
        }
        if (like.getStatus() != LikeStatus.PENDING) {
            throw new LikeBusinessException(LikeErrorCode.LIKE_ALREADY_RESOLVED);
        }

        like.resolveLike(LikeStatus.REJECTED, LocalDateTime.now());
    }

    private void validateLikeId(Long likeId) {
        if (likeId == null || likeId <= 0) {
            throw new RequestValidationException();
        }
    }
}
