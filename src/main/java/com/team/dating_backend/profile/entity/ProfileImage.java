package com.team.dating_backend.profile.entity;

import com.team.dating_backend.file.entity.File;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "profile_images",
    indexes = @Index(
        name = "idx_profile_images_profile_active_order",
        columnList = "profile_id, deleted_at, display_order"
    )
)
public class ProfileImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "image_id",
        nullable = false
    )
    private File image;

    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "profile_id",
        nullable = false
    )
    private Profile profile;

    @Column(
        name = "display_order",
        nullable = false
    )
    private short displayOrder;

    @Column(
        name = "is_frontal",
        nullable = false
    )
    private boolean frontal;

    @CreationTimestamp
    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(
        name = "updated_at",
        nullable = false
    )
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public ProfileImage(Profile profile, File image, short displayOrder, boolean frontal) {
        this.profile = profile;
        this.image = image;
        this.displayOrder = displayOrder;
        this.frontal = frontal;
    }

    public void updateProfileImage(short displayOrder, boolean frontal) {
        this.displayOrder = displayOrder;
        this.frontal = frontal;
    }

    public void markDeleted(LocalDateTime deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }
}
