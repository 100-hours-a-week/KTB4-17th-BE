package com.team.dating_backend.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomPresenceRegistryTest {

    private final AtomicLong nanoTime = new AtomicLong();
    private ChatRoomPresenceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChatRoomPresenceRegistry(nanoTime::get);
    }

    @Test
    void 다른_연결이_같은_방을_보고_있으면_일부_연결이_종료되어도_상태를_유지한다() {
        registry.enter(1L, "pc", 10L);
        registry.enter(1L, "mobile", 10L);
        registry.enter(2L, "other-user", 20L);

        registry.removeConnection(1L, "pc");
        registry.removeConnection(1L, "pc");

        assertThat(registry.isViewingChatRoom(1L, 10L)).isTrue();
        registry.leave(1L, "mobile", 10L);
        assertThat(registry.isViewingChatRoom(1L, 10L)).isFalse();
        assertThat(registry.isViewingChatRoom(2L, 20L)).isTrue();
    }

    @Test
    void 만료된_상태는_조회에서_제외하고_유지_신호만으로_다시_등록하지_않는다() {
        registry.enter(1L, "pc", 10L);
        nanoTime.set(Duration.ofSeconds(60).toNanos());

        assertThat(registry.isViewingChatRoom(1L, 10L)).isFalse();
        registry.heartbeat(1L, "pc", 10L);
        assertThat(registry.isViewingChatRoom(1L, 10L)).isFalse();
        registry.enter(1L, "pc", 10L);
        assertThat(registry.isViewingChatRoom(1L, 10L)).isTrue();
        nanoTime.addAndGet(Duration.ofSeconds(60).toNanos());
        registry.evictExpiredPresences();
        assertThat(registry.isViewingChatRoom(1L, 10L)).isFalse();
    }

    @Test
    void 유효한_유지_신호는_그_시점부터_만료_시간을_갱신한다() {
        registry.enter(1L, "pc", 10L);
        nanoTime.set(Duration.ofSeconds(59).toNanos());
        registry.heartbeat(1L, "pc", 10L);
        nanoTime.set(Duration.ofSeconds(60).toNanos());
        assertThat(registry.isViewingChatRoom(1L, 10L)).isTrue();
        nanoTime.set(Duration.ofSeconds(119).toNanos());
        assertThat(registry.isViewingChatRoom(1L, 10L)).isFalse();
    }

    @Test
    void 이전_방의_이탈과_유지_신호는_현재_방을_해제하거나_갱신하지_않는다() {
        registry.enter(1L, "pc", 10L);
        registry.enter(1L, "pc", 20L);
        registry.leave(1L, "pc", 10L);
        assertThat(registry.isViewingChatRoom(1L, 20L)).isTrue();
        nanoTime.set(Duration.ofSeconds(59).toNanos());
        registry.heartbeat(1L, "pc", 10L);
        nanoTime.set(Duration.ofSeconds(60).toNanos());
        assertThat(registry.isViewingChatRoom(1L, 20L)).isFalse();
    }
}
