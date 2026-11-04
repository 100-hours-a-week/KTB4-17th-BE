package com.team.dating_backend.profile.cache;

import com.team.dating_backend.profile.dto.event.ProfileCacheEvictionRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ProfileCacheEvictionListener {

    private final UserProfileCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evict(ProfileCacheEvictionRequestedEvent event) {
        cache.evict(event.userId());
    }
}
