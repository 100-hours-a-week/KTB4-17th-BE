package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.request.ChatRoomPresenceRequest;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomPresenceAction;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.presence.ChatRoomPresenceDisconnectListener;
import com.team.dating_backend.chat.presence.ChatRoomPresenceRegistry;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class ChatRoomPresenceServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ROOM_ID = 30L;
    private static final String SESSION_ID = "socket";

    private ChatRoomRepository roomRepository;
    private UserRepository userRepository;
    private ChatRoomPresenceRegistry registry;
    private ChatRoomPresenceService service;
    private ChatRoom room;
    private ChatParticipant viewer;
    private User user;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        roomRepository = mock(ChatRoomRepository.class);
        userRepository = mock(UserRepository.class);
        registry = new ChatRoomPresenceRegistry();
        service = new ChatRoomPresenceService(roomRepository, userRepository, registry);
        attributes = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        room = new ChatRoom(new Match(USER_ID, 2L, now), now);
        viewer = room.addParticipant(USER_ID);
        room.addParticipant(2L);
        user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(roomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
    }

    @Test
    void 종료된_방도_남아있는_참여자는_입장과_상태_유지가_가능하다() {
        room.end(ChatRoomEndReason.USER_LEFT_CHAT, LocalDateTime.now());

        update(ChatRoomPresenceAction.ENTER);
        update(ChatRoomPresenceAction.HEARTBEAT);

        assertThat(registry.isViewingChatRoom(USER_ID, ROOM_ID)).isTrue();
    }

    @Test
    void 방을_나간_참여자의_유지_요청은_거부하고_기존_등록을_해제한다() {
        registry.enter(USER_ID, SESSION_ID, ROOM_ID);
        viewer.leave(LocalDateTime.now());

        assertAccessDenied();
        assertThat(registry.isViewingChatRoom(USER_ID, ROOM_ID)).isFalse();
    }

    @Test
    void 비활성_회원은_상태를_등록할_수_없다() {
        given(user.getStatus()).willReturn(UserStatus.WITHDRAWN);

        assertAccessDenied();
        assertThat(registry.isViewingChatRoom(USER_ID, ROOM_ID)).isFalse();
    }

    @Test
    void 화면_이탈은_DB_권한_조회_없이_자신의_연결_상태를_정리한다() {
        registry.enter(USER_ID, SESSION_ID, ROOM_ID);
        registry.enter(USER_ID, "another-socket", ROOM_ID);

        update(ChatRoomPresenceAction.LEAVE);

        verifyNoInteractions(roomRepository, userRepository);
        assertThat(registry.isViewingChatRoom(USER_ID, ROOM_ID)).isTrue();
    }

    @Test
    void 입장_검증_도중_연결이_종료되면_검증이_끝나도_상태를_다시_등록하지_않는다()
        throws Exception {
        CountDownLatch validating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(roomRepository.findById(ROOM_ID)).willAnswer(invocation -> {
            validating.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(room);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> update(ChatRoomPresenceAction.ENTER));
            assertThat(validating.await(3, TimeUnit.SECONDS)).isTrue();

            StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            headers.setSessionId(SESSION_ID);
            headers.setSessionAttributes(attributes);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new ServiceAuthenticationPrincipal(USER_ID), null, Set.of());
            var event = new SessionDisconnectEvent(
                this, MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()),
                SESSION_ID, CloseStatus.NORMAL, authentication);
            new ChatRoomPresenceDisconnectListener(registry).onDisconnect(event);
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);

            assertThat(registry.isViewingChatRoom(USER_ID, ROOM_ID)).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void update(ChatRoomPresenceAction action) {
        service.updatePresence(
            USER_ID, SESSION_ID, new ChatRoomPresenceRequest(ROOM_ID, action), attributes);
    }

    private void assertAccessDenied() {
        assertThatThrownBy(() -> update(ChatRoomPresenceAction.HEARTBEAT))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ACCESS_DENIED));
    }
}
