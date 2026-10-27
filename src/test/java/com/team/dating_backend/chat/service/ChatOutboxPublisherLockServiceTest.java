package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.common.lock.repository.PublisherLockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatOutboxPublisherLockServiceTest {

    private static final String LOCK_NAME = "chat-message-outbox-publisher";
    private static final long LOCK_TTL_MICROS = 10_000_000L;

    @Mock
    private PublisherLockRepository lockRepository;

    @InjectMocks
    private ChatOutboxPublisherLockService service;

    @Test
    void 락을_획득하면_true를_반환한다() {
        given(lockRepository.tryAcquireOrExtend(
            eq(LOCK_NAME), anyString(), eq(LOCK_TTL_MICROS))).willReturn(1);

        assertThat(service.tryAcquireOrExtend()).isTrue();

        verify(lockRepository).tryAcquireOrExtend(
            eq(LOCK_NAME), anyString(), eq(LOCK_TTL_MICROS));
    }

    @Test
    void 락을_획득하지_못하면_false를_반환한다() {
        given(lockRepository.tryAcquireOrExtend(
            eq(LOCK_NAME), anyString(), eq(LOCK_TTL_MICROS))).willReturn(0);

        assertThat(service.tryAcquireOrExtend()).isFalse();
    }

    @Test
    void 락을_연장할_때는_동일한_인스턴스_ID를_사용한다() {
        service.tryAcquireOrExtend();
        service.tryAcquireOrExtend();

        ArgumentCaptor<String> instanceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(lockRepository, times(2)).tryAcquireOrExtend(
            eq(LOCK_NAME),
            instanceIdCaptor.capture(),
            eq(LOCK_TTL_MICROS));

        assertThat(instanceIdCaptor.getAllValues())
            .doesNotContainNull()
            .doesNotContain("")
            .containsOnly(instanceIdCaptor.getValue());
    }
}
