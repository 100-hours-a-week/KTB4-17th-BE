package com.team.dating_backend.activityregion.dto.response;

import com.team.dating_backend.activityregion.entity.ActivityRegion;

public record ActivityRegionSearchItem(
        Long activityRegionId, String regionCode, String provinceName, String regionName) {

    public static ActivityRegionSearchItem from(ActivityRegion activityRegion) {
        return new ActivityRegionSearchItem(
                activityRegion.getId(),
                activityRegion.getRegionCode(),
                activityRegion.getProvinceName(),
                activityRegion.getRegionName());
    }
}
