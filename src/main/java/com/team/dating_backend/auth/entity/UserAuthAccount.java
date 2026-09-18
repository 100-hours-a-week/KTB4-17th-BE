package com.team.dating_backend.auth.entity;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "user_auth_accounts",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_user_auth_accounts_provider_provider_user_id",
                        columnNames = {"provider", "provider_user_id"}))
public class UserAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    public static UserAuthAccount create(
            User user, AuthProvider provider, String providerUserId, LocalDateTime now) {

        UserAuthAccount userAuthAccount = new UserAuthAccount();
        userAuthAccount.user = user;
        userAuthAccount.provider = provider;
        userAuthAccount.providerUserId = providerUserId;
        userAuthAccount.linkedAt = now;

        return userAuthAccount;
    }
}
