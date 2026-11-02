package com.team.dating_backend.profile.service;

import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.dto.response.PopularProfilesResponse;
import com.team.dating_backend.profile.repository.PopularProfileRow;
import java.util.ArrayList;
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
public class PopularProfileGetService {

    private static final int RANKING_SIZE = 10;

    private final LikeRepository likeRepository;
    private final ProfileImageGetService profileImageGetService;

    @Transactional(readOnly = true)
    public PopularProfilesResponse getPopularProfiles() {
        List<PopularProfileRow> rows = likeRepository.findPopularProfiles(PageRequest.of(0, RANKING_SIZE));
        Map<Long, List<ProfileImageAccessResult>> images = profileImageGetService
            .getProfileImagesByUserIds(rows.stream().map(PopularProfileRow::userId).toList());
        List<PopularProfilesResponse.Item> profiles = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PopularProfileRow row = rows.get(i);
            String region = Stream.of(row.provinceName(), row.regionName())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
            ProfileImageAccessResult image = images.getOrDefault(row.userId(), List.of())
                .stream().findFirst().orElse(null);
            PopularProfilesResponse.Image thumbnail = image == null
                ? null
                : new PopularProfilesResponse.Image(image.fileId(), image.imageUrl(), image.expiresAt());
            profiles.add(new PopularProfilesResponse.Item(
                i + 1, row.userId(), row.nickname(), region.isBlank() ? null : region, row.likeCount(), thumbnail));
        }
        return new PopularProfilesResponse(profiles);
    }
}
