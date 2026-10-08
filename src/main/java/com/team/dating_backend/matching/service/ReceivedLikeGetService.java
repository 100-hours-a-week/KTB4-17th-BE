package com.team.dating_backend.matching.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.matching.dto.response.ReceivedLikeItemResponse;
import com.team.dating_backend.matching.dto.response.ReceivedLikePageInfo;
import com.team.dating_backend.matching.dto.response.ReceivedLikeSenderResponse;
import com.team.dating_backend.matching.dto.response.ReceivedLikesGetResponse;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.ReceivedLikeItem;
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
public class ReceivedLikeGetService {

    private static final int ITEMS_PER_REQUEST = 20;
    private static final short REPRESENTATIVE_IMAGE_ORDER = 1;

    private final UserRepository userRepository;
    private final LikeRepository likeRepository;
    private final ProfileImageGetService profileImageGetService;

    @Transactional(readOnly = true)
    public ReceivedLikesGetResponse getReceivedLikes(Long receiverId, Long cursor) {
        validateCursor(cursor);
        User receiver = userRepository.findById(receiverId)
            .orElseThrow(() -> new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
        if (receiver.getStatus() != UserStatus.ACTIVE) {
            throw new LikeBusinessException(LikeErrorCode.RECEIVER_NOT_ACTIVE);
        }

        List<ReceivedLikeItem> rows = likeRepository.findReceivedPendingLikes(
            receiverId, cursor, PageRequest.of(0, ITEMS_PER_REQUEST + 1));
        boolean hasNext = rows.size() > ITEMS_PER_REQUEST;
        List<ReceivedLikeItem> responseRows = rows.stream()
            .limit(ITEMS_PER_REQUEST)
            .toList();
        Map<Long, List<ProfileImageAccessResult>> profileImagesByMemberId = profileImageGetService
            .getProfileImagesByMemberIds(responseRows.stream()
                .map(ReceivedLikeItem::memberId)
                .distinct()
                .toList());

        LocalDate today = LocalDate.now();
        List<ReceivedLikeItemResponse> items = responseRows.stream()
            .map(row -> toItemResponse(
                row,
                today,
                profileImagesByMemberId.getOrDefault(row.memberId(), List.of())))
            .toList();
        Long nextCursor = hasNext ? items.getLast().likeId() : null;
        return new ReceivedLikesGetResponse(
            items, new ReceivedLikePageInfo(nextCursor, hasNext));
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor <= 0) {
            throw new RequestValidationException();
        }
    }

    private ReceivedLikeItemResponse toItemResponse(
        ReceivedLikeItem row,
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

        ReceivedLikeSenderResponse sender = new ReceivedLikeSenderResponse(
            row.memberId(),
            row.nickname(),
            profileImageUrl,
            age,
            row.job(),
            region);
        return new ReceivedLikeItemResponse(
            row.likeId(), sender, row.status(), row.createdAt());
    }
}
