package com.team.dating_backend.activityregion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(name = "activity_regions")
@Check(constraints = "representative_latitude BETWEEN -90 AND 90 "
    + "AND representative_longitude BETWEEN -180 AND 180")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_code", nullable = false, unique = true, length = 30)
    private String regionCode;

    @Column(name = "province_name", nullable = false, length = 50)
    private String provinceName;

    @Column(name = "region_name", nullable = false, length = 50)
    private String regionName;

    @Column(name = "representative_latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal representativeLatitude;

    @Column(name = "representative_longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal representativeLongitude;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ActivityRegion(
        String regionCode,
        String provinceName,
        String regionName,
        BigDecimal representativeLatitude,
        BigDecimal representativeLongitude) {
        this.regionCode = regionCode;
        this.provinceName = provinceName;
        this.regionName = regionName;
        this.representativeLatitude = representativeLatitude;
        this.representativeLongitude = representativeLongitude;
    }
}
