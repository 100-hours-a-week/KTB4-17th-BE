package com.team.dating_backend.profile.service;

import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.repository.PopularProfileRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PopularProfileRankingService {

    private static final int RANKING_SIZE = 10;

    private final LikeRepository likeRepository;

    @Transactional(readOnly = true)
    public List<PopularProfileRow> getTopProfiles() {
        return likeRepository.findPopularProfiles(PageRequest.of(0, RANKING_SIZE));
    }

    @Transactional(readOnly = true)
    public boolean isInTopProfiles(Long userId) {
        return getTopProfiles().stream().anyMatch(row -> row.userId().equals(userId));
    }
}
