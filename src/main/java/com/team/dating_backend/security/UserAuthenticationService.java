package com.team.dating_backend.security;

import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAuthenticationService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean isAuthenticationAllowed(Long userId) {
        return userId != null
            && userRepository.existsByIdAndStatusNot(userId, UserStatus.WITHDRAWN);
    }
}
