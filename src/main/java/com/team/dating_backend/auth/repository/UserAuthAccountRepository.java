package com.team.dating_backend.auth.repository;

import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthProvider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAuthAccountRepository extends JpaRepository<UserAuthAccount, Long> {

    Optional<UserAuthAccount> findByProviderAndProviderUserId(
            AuthProvider provider, String providerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select account
            from UserAuthAccount account
            where account.provider = :provider
              and account.providerUserId = :providerUserId
            """)
    Optional<UserAuthAccount> findForUpdateByProviderAndProviderUserId(
            @Param("provider") AuthProvider provider,
            @Param("providerUserId") String providerUserId);
}
