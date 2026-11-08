package com.team.dating_backend.chat.delegation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.service.ChatDelegationDeliveryService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationGenerationPersistenceService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationGenerationWorker;
import com.team.dating_backend.chat.delegation.service.ChatDelegationTerminationService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatDelegationAsyncConfigTest {

    @Test
    void 채팅_Worker는_자체_비동기_설정만으로_전용_스레드에서_실행한다() {
        var delivery = mock(ChatDelegationDeliveryService.class);
        var threadName = new CompletableFuture<String>();
        doAnswer(invocation -> {
            threadName.complete(Thread.currentThread().getName());
            return true;
        }).when(delivery).deliver(eq(31L), any());

        new ApplicationContextRunner()
            .withUserConfiguration(ChatDelegationAsyncConfig.class, ChatDelegationGenerationWorker.class)
            .withPropertyValues("app.chat.runtime-enabled=true")
            .withBean(ChatDelegationGenerationPersistenceService.class,
                () -> mock(ChatDelegationGenerationPersistenceService.class))
            .withBean(ChatDelegationTerminationService.class, () -> mock(ChatDelegationTerminationService.class))
            .withBean(ChatDelegationDeliveryService.class, () -> delivery)
            .withBean(ChatDelegationAiClient.class, () -> mock(ChatDelegationAiClient.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                var callerThread = Thread.currentThread().getName();

                context.getBean(ChatDelegationGenerationWorker.class).deliver(31L);

                assertThat(threadName.get(5, TimeUnit.SECONDS)).startsWith("chat-delegation-")
                    .isNotEqualTo(callerThread);
            });
    }
}
