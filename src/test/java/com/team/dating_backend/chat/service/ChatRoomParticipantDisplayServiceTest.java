package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.read.ChatParticipantDisplay;
import com.team.dating_backend.chat.repository.ChatParticipantDisplayRow;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomParticipantDisplayRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomParticipantDisplayServiceTest {

    private ChatRoomParticipantDisplayRepository repository;
    private ChatParticipantRepository chatParticipantRepository;
    private ProfileImageGetService profileImageGetService;
    private UserBlockRepository userBlockRepository;
    private UserRepository userRepository;
    private ChatRoomParticipantDisplayService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatRoomParticipantDisplayRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        profileImageGetService = mock(ProfileImageGetService.class);
        userBlockRepository = mock(UserBlockRepository.class);
        userRepository = mock(UserRepository.class);
        service = new ChatRoomParticipantDisplayService(
            repository, chatParticipantRepository, profileImageGetService,
            userBlockRepository, userRepository);
    }

    @Test
    void 사용자_ID가_없으면_빈_결과를_반환하고_저장소를_호출하지_않는다() {
        assertThat(service.findDisplays(1L, List.of())).isEmpty();
        verifyNoInteractions(repository, chatParticipantRepository, profileImageGetService,
            userBlockRepository, userRepository);
    }

    @Test
    void 차단하거나_탈퇴한_사용자는_ID와_프로필을_숨기고_활성_사용자는_프로필_정보를_반환한다() {
        List<Long> userIds = List.of(2L, 3L, 4L);
        given(userBlockRepository.findActiveBlockerUserIds(1L, Set.of(2L, 3L, 4L)))
            .willReturn(List.of(2L));
        given(userRepository.findIdsByStatus(Set.of(2L, 3L, 4L), UserStatus.WITHDRAWN))
            .willReturn(List.of(3L));
        given(chatParticipantRepository.findLeftUserIdsVisibleTo(
            1L, Set.of(2L, 3L, 4L))).willReturn(List.of());
        given(repository.findByUserIds(List.of(4L))).willReturn(List.of(
            new ChatParticipantDisplayRow(4L, "사용자B")));
        given(profileImageGetService.getProfileImagesByUserIds(List.of(4L)))
            .willReturn(Map.of(4L, List.of(new ProfileImageAccessResult(
                30L, (short) 1, "https://example.com/profile.jpg", Instant.now()))));

        Map<Long, ChatParticipantDisplay> result = service.findDisplays(1L, userIds);

        assertThat(result.get(2L)).isEqualTo(
            new ChatParticipantDisplay(null, "알 수 없음", null));
        assertThat(result.get(3L)).isEqualTo(
            new ChatParticipantDisplay(null, "알 수 없음", null));
        assertThat(result.get(4L)).isEqualTo(new ChatParticipantDisplay(
            4L, "사용자B", "https://example.com/profile.jpg"));
        verify(repository).findByUserIds(List.of(4L));
        verify(profileImageGetService).getProfileImagesByUserIds(List.of(4L));
    }

    @Test
    void 채팅방을_나간_사용자는_ID와_프로필을_알_수_없음으로_숨긴다() {
        Set<Long> userIds = Set.of(2L);
        given(userBlockRepository.findActiveBlockerUserIds(1L, userIds))
            .willReturn(List.of());
        given(userRepository.findIdsByStatus(userIds, UserStatus.WITHDRAWN))
            .willReturn(List.of());
        given(chatParticipantRepository.findLeftUserIdsVisibleTo(1L, userIds))
            .willReturn(List.of(2L));

        Map<Long, ChatParticipantDisplay> result = service.findDisplays(1L, userIds);

        assertThat(result.get(2L)).isEqualTo(
            new ChatParticipantDisplay(null, "알 수 없음", null));
        verifyNoInteractions(repository, profileImageGetService);
    }
}
