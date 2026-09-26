package com.team.dating_backend.matching.service;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.MatchRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeSendService {

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final LikeRepository likeRepository;
    private final MatchRepository matchRepository;
    private final ChatRoomCreateService chatRoomCreateService;

    @Transactional
    public LikeCreateResponse sendLike(Long senderId, Long receiverId) {
        if (senderId.equals(receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.SELF_LIKE_NOT_ALLOWED);
        }

        User sender = requireMember(senderId);
        User receiver = requireMember(receiverId);

        if (sender.getStatus() != UserStatus.ACTIVE) {
            throw new LikeBusinessException(LikeErrorCode.SENDER_NOT_ACTIVE);
        }
        if (receiver.getStatus() != UserStatus.ACTIVE
            || userBlockRepository.existsActiveBlockBetween(senderId, receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND);
        }
        if (matchRepository.existsBetween(senderId, receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.MATCH_ALREADY_EXISTS);
        }
        if (likeRepository
            .findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(
                senderId, receiverId, LikeStatus.PENDING)
            .isPresent()) {
            throw new LikeBusinessException(LikeErrorCode.DUPLICATE_PENDING_LIKE);
        }

        Optional<Like> firstLike = likeRepository.findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(
            receiverId, senderId, LikeStatus.PENDING);
        Like like = likeRepository.save(new Like(senderId, receiverId, LocalDateTime.now()));
        if (firstLike.isPresent()) {
            Like earlierLike = firstLike.get();
            LocalDateTime matchedAt = LocalDateTime.now();
            earlierLike.resolveLike(LikeStatus.MATCHED, matchedAt);
            like.resolveLike(LikeStatus.MATCHED, matchedAt);
            Match match = matchRepository.save(
                new Match(earlierLike.getSenderId(), earlierLike.getReceiverId(), matchedAt));
            chatRoomCreateService.createChatRoom(
                match.getId(), match.getSenderId(), match.getReceiverId(), matchedAt);
        }
        return new LikeCreateResponse(like.getId(), like.getStatus());
    }

    private User requireMember(Long userId) {
        return userRepository
            .findById(userId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
    }
}
