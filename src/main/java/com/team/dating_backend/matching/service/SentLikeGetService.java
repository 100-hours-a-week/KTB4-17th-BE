package com.team.dating_backend.matching.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.matching.dto.response.SentLikeItemResponse;
import com.team.dating_backend.matching.dto.response.SentLikePageInfo;
import com.team.dating_backend.matching.dto.response.SentLikeReceiverResponse;
import com.team.dating_backend.matching.dto.response.SentLikesGetResponse;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.SentLikeItem;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SentLikeGetService {

    private static final int ITEMS_PER_REQUEST = 20;
    private static final short REPRESENTATIVE_IMAGE_ORDER = 1;

    private final UserRepository userRepository;
    private final LikeRepository likeRepository;
    private final ProfileImageGetService profileImageGetService;

    @Transactional(readOnly = true)
    public SentLikesGetResponse getSentLikes(Long senderId, Long cursor) {
        validateCursor(cursor);
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
        if (sender.getStatus() != UserStatus.ACTIVE) {
            throw new LikeBusinessException(LikeErrorCode.SENDER_NOT_ACTIVE);
        }

        List<SentLikeItem> rows = likeRepository.findSentPendingLikes(
            senderId, cursor, PageRequest.of(0, ITEMS_PER_REQUEST + 1));
        boolean hasNext = rows.size() > ITEMS_PER_REQUEST;
        List<SentLikeItem> responseRows = rows.stream()
            .limit(ITEMS_PER_REQUEST)
            .toList();
        Map<Long, List<ProfileImageAccessResult>> profileImagesByMemberId = profileImageGetService
            .getProfileImagesByMemberIds(responseRows.stream()
                .map(SentLikeItem::memberId)
                .distinct()
                .toList());

        LocalDate today = LocalDate.now();
        List<SentLikeItemResponse> items = responseRows.stream()
            .map(row -> toItemResponse(
                row,
                today,
                profileImagesByMemberId.getOrDefault(row.memberId(), List.of())))
            .toList();
        Long nextCursor = hasNext ? items.getLast().likeId() : null;
        return new SentLikesGetResponse(items, new SentLikePageInfo(nextCursor, hasNext));
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor <= 0) {
            throw new RequestValidationException();
        }
    }

    private SentLikeItemResponse toItemResponse(
        SentLikeItem row,
        LocalDate today,
        List<ProfileImageAccessResult> profileImages) {
        String profileImageUrl = profileImages.stream()
            .filter(image -> image.displayOrder() == REPRESENTATIVE_IMAGE_ORDER)
            .map(ProfileImageAccessResult::imageUrl)
            .findFirst()
            .orElse(null);
        Integer age = row.birthDate() == null
            ? null
            : Period.between(row.birthDate(), today).getYears();
        String region = Stream.of(row.provinceName(), row.regionName())
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.joining(" "));
        if (region.isEmpty()) {
            region = null;
        }

        SentLikeReceiverResponse receiver = new SentLikeReceiverResponse(
            row.memberId(),
            row.nickname(),
            profileImageUrl,
            age,
            row.job(),
            region);
        return new SentLikeItemResponse(
            row.likeId(), receiver, row.status(), row.createdAt());
    }
}
