package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.repository.ChatParticipantDisplayRow;
import com.team.dating_backend.chat.repository.ChatRoomParticipantDisplayRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomParticipantDisplayService {

    private static final short REPRESENTATIVE_IMAGE_ORDER = 1;

    private final ChatRoomParticipantDisplayRepository participantDisplayRepository;
    private final ProfileImageGetService profileImageGetService;

    @Transactional(readOnly = true)
    public Map<Long, String> findNicknames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return participantDisplayRepository.findByUserIds(userIds).stream()
            .collect(Collectors.toUnmodifiableMap(
                ChatParticipantDisplayRow::userId,
                ChatParticipantDisplayRow::nickname));
    }

    public Map<Long, String> findProfileImageUrls(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ProfileImageAccessResult>> profileImagesByMemberId =
            profileImageGetService.getProfileImagesByMemberIds(userIds);
        Map<Long, String> profileImageUrlsByMemberId = new LinkedHashMap<>();
        profileImagesByMemberId.forEach((userId, images) -> images.stream()
            .filter(image -> image.displayOrder() == REPRESENTATIVE_IMAGE_ORDER)
            .map(ProfileImageAccessResult::imageUrl)
            .findFirst()
            .ifPresent(imageUrl -> profileImageUrlsByMemberId.put(userId, imageUrl)));
        return Map.copyOf(profileImageUrlsByMemberId);
    }
}
