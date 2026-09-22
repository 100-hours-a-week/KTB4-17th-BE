package com.team.dating_backend.activityregion.repository;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRegionRepository extends JpaRepository<ActivityRegion, Long> {

    @Query(
            """
            SELECT activityRegion
            FROM ActivityRegion activityRegion
            WHERE CONCAT(activityRegion.provinceName, ' ', activityRegion.regionName)
                    LIKE CONCAT('%', :query, '%')
            ORDER BY activityRegion.provinceName ASC,
                    activityRegion.regionName ASC,
                    activityRegion.regionCode ASC
            """)
    List<ActivityRegion> findByDisplayNameContaining(@Param("query") String query);
}
