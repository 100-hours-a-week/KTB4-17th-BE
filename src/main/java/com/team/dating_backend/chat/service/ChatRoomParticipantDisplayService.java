package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.read.ChatParticipantDisplay;
import com.team.dating_backend.chat.repository.ChatParticipantDisplayRow;
import com.team.dating_backend.chat.repository.ChatRoomParticipantDisplayRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.user.repository.UserBlockRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomParticipantDisplayService {

    private static final short REPRESENTATIVE_IMAGE_ORDER = 1;
    private static final String UNKNOWN_NICKNAME = "알 수 없음";

    private final ChatRoomParticipantDisplayRepository participantDisplayRepository;
    private final ProfileImageGetService profileImageGetService;
    private final UserBlockRepository userBlockRepository;

    @Transactional(readOnly = true)
    public Map<Long, ChatParticipantDisplay> findDisplays(
        Long viewerUserId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> distinctUserIds = new LinkedHashSet<>(userIds);
        Set<Long> maskedUserIds = Set.copyOf(userBlockRepository.findActiveBlockerUserIds(
            viewerUserId, distinctUserIds));
        List<Long> visibleUserIds = distinctUserIds.stream()
            .filter(userId -> !maskedUserIds.contains(userId))
            .toList();

        Map<Long, String> nicknames = findNicknames(visibleUserIds);
        Map<Long, String> profileImageUrls = findProfileImageUrls(visibleUserIds);
        Map<Long, ChatParticipantDisplay> displays = new LinkedHashMap<>();
        distinctUserIds.forEach(userId -> displays.put(
            userId,
            maskedUserIds.contains(userId)
                ? new ChatParticipantDisplay(null, UNKNOWN_NICKNAME, null)
                : new ChatParticipantDisplay(
                    userId, nicknames.get(userId), profileImageUrls.get(userId))));
        return Map.copyOf(displays);
    }

    private Map<Long, String> findNicknames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return participantDisplayRepository.findByUserIds(userIds).stream()
            .collect(Collectors.toUnmodifiableMap(
                ChatParticipantDisplayRow::userId,
                ChatParticipantDisplayRow::nickname));
    }

    private Map<Long, String> findProfileImageUrls(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ProfileImageAccessResult>> profileImagesByUserId = profileImageGetService
            .getProfileImagesByUserIds(userIds);
        Map<Long, String> profileImageUrlsByUserId = new LinkedHashMap<>();
        profileImagesByUserId.forEach((userId, images) -> images.stream()
            .filter(image -> image.displayOrder() == REPRESENTATIVE_IMAGE_ORDER)
            .map(ProfileImageAccessResult::imageUrl)
            .findFirst()
            .ifPresent(imageUrl -> profileImageUrlsByUserId.put(userId, imageUrl)));
        return Map.copyOf(profileImageUrlsByUserId);
    }
}
