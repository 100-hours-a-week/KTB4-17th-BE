package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationAccessService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void assertActiveUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new NotificationBusinessException(
                NotificationErrorCode.REQUESTER_NOT_ACTIVE));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE);
        }
    }
}
