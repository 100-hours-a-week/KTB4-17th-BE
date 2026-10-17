package com.team.dating_backend.chat.presence;

import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class ChatRoomPresenceDisconnectListener {

    private final ChatRoomPresenceRegistry presenceRegistry;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (!(event.getUser() instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof ServiceAuthenticationPrincipal user)
            || user.userId() == null || user.userId() <= 0
            || !StringUtils.hasText(event.getSessionId())) {
            return;
        }

        Map<String, Object> attributes = SimpMessageHeaderAccessor.wrap(event.getMessage())
            .getSessionAttributes();
        if (attributes == null) {
            presenceRegistry.removeConnection(user.userId(), event.getSessionId());
            return;
        }
        synchronized (attributes) {
            attributes.put(ChatRoomPresenceRegistry.SESSION_CLOSED_ATTRIBUTE, true);
            presenceRegistry.removeConnection(user.userId(), event.getSessionId());
        }
    }
}
