package com.team.dating_backend.matching.service;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.MatchRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.MatchingNotificationTemplate;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Instant;
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
    private final UserPairLockRepository userPairLockRepository;
    private final ChatRoomCreateService chatRoomCreateService;
    private final NotificationRequestService notificationRequestService;
    private final ProfileRepository profileRepository;

    @Transactional
    public LikeCreateResponse sendLike(Long senderId, Long receiverId) {
        if (senderId.equals(receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.SELF_LIKE_NOT_ALLOWED);
        }

        userPairLockRepository.acquire(senderId, receiverId);

        User sender = requireUserForUpdate(senderId);
        User receiver = requireUserForUpdate(receiverId);

        if (sender.getStatus() == UserStatus.WITHDRAWN) {
            throw new AuthenticationRequiredException();
        }
        if (sender.getStatus() != UserStatus.ACTIVE) {
            throw new LikeBusinessException(LikeErrorCode.SENDER_NOT_ACTIVE);
        }
        if (receiver.getStatus() != UserStatus.ACTIVE
            || userBlockRepository.existsActiveBlockBetween(senderId, receiverId)) {
            throw new LikeBusinessException(LikeErrorCode.USER_NOT_FOUND);
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

        Optional<Like> firstLike = likeRepository
            .findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(
                receiverId, senderId, LikeStatus.PENDING);
        Like like = likeRepository.save(new Like(senderId, receiverId, LocalDateTime.now()));
        if (firstLike.isPresent()) {
            Like earlierLike = firstLike.get();
            LocalDateTime matchedAt = LocalDateTime.now();
            earlierLike.resolveLike(LikeStatus.MATCHED, matchedAt);
            like.resolveLike(LikeStatus.MATCHED, matchedAt);
            Match match = matchRepository.save(
                new Match(earlierLike.getSenderId(), earlierLike.getReceiverId(), matchedAt));
            Long chatRoomId = chatRoomCreateService.createChatRoom(match, matchedAt);
            MatchingNotificationTemplate template = MatchingNotificationTemplate.MATCH;
            String sourceEventId = "MATCH_CREATED:" + match.getId();
            Instant requestedAt = Instant.now();
            notificationRequestService.requestNotification(new NotificationCreateRequested(
                NotificationCreateRequested.CURRENT_SCHEMA_VERSION, sourceEventId,
                match.getSenderId(), NotificationType.MATCH,
                template.getTitle(), template.getBody(), template.getTargetType(), chatRoomId, requestedAt));
            notificationRequestService.requestNotification(new NotificationCreateRequested(
                NotificationCreateRequested.CURRENT_SCHEMA_VERSION, sourceEventId,
                match.getReceiverId(), NotificationType.MATCH,
                template.getTitle(), template.getBody(), template.getTargetType(), chatRoomId, requestedAt));
        } else {
            String senderNickname = profileRepository.findByUserIdAndDeletedAtIsNull(senderId)
                .map(Profile::getNickname)
                .orElse(null);
            MatchingNotificationTemplate template = MatchingNotificationTemplate.LIKE;
            Instant requestedAt = Instant.now();
            notificationRequestService.requestNotification(new NotificationCreateRequested(
                NotificationCreateRequested.CURRENT_SCHEMA_VERSION, "LIKE_SENT:" + like.getId(),
                receiverId, NotificationType.LIKE,
                template.getTitle(), template.formatBody(senderNickname),
                template.getTargetType(), senderId, requestedAt));
        }
        return new LikeCreateResponse(like.getId(), like.getStatus());
    }

    private User requireUserForUpdate(Long userId) {
        return userRepository
            .findByIdForUpdate(userId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.USER_NOT_FOUND));
    }
}
