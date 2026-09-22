package com.team.dating_backend.activityregion.service;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityRegionSearchService {

    private final ActivityRegionRepository activityRegionRepository;

    @Transactional(readOnly = true)
    public List<ActivityRegion> searchRegions(String query) {
        return activityRegionRepository.findByDisplayNameContaining(query);
    }
}
