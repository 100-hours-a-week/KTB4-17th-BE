package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationAsyncConfig;
import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.GenerationInput;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AiSimulationWorkerAsyncTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(AiSimulationAsyncConfig.class, TestConfiguration.class)
        .withPropertyValues("app.ai-simulation.async-shutdown-wait-seconds=1");

    @Test
    void Worker는_전용_스레드에서_AI를_기다리고_호출자는_먼저_반환된다() {
        contextRunner.run(context -> {
            AiSimulationPersistenceService persistence = context.getBean(AiSimulationPersistenceService.class);
            AiSimulationAiClient client = context.getBean(AiSimulationAiClient.class);
            AiSimulationResponseValidator validator = context.getBean(AiSimulationResponseValidator.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicReference<Boolean> transactionActive = new AtomicReference<>();
            given(persistence.loadGenerationInput(SESSION_ID))
                .willReturn(Optional.of(new GenerationInput(SESSION_ID, USER_ID, TARGET_ID)));
            given(client.run(new SimulationRequest("7", "8", 10))).willAnswer(invocation -> {
                threadName.set(Thread.currentThread().getName());
                transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("AI release timed out");
                }
                return aiResponse();
            });
            doAnswer(invocation -> {
                completed.countDown();
                return null;
            }).when(persistence).complete(SESSION_ID, aiResponse());

            try {
                context.getBean(AiSimulationWorker.class).generate(SESSION_ID);

                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(threadName.get()).startsWith("ai-simulation-");
                assertThat(threadName.get()).isNotEqualTo(Thread.currentThread().getName());
                assertThat(transactionActive.get()).isFalse();
                verify(persistence, never()).complete(SESSION_ID, aiResponse());

                release.countDown();
                assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
                verify(validator).validate(aiResponse(), USER_ID, TARGET_ID);
                verify(persistence, never()).fail(SESSION_ID);
            } finally {
                release.countDown();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationProperties.class)
    static class TestConfiguration {

        @Bean
        AiSimulationPersistenceService persistenceService() {
            return mock(AiSimulationPersistenceService.class);
        }

        @Bean
        AiSimulationAiClient aiClient() {
            return mock(AiSimulationAiClient.class);
        }

        @Bean
        AiSimulationResponseValidator validator() {
            return mock(AiSimulationResponseValidator.class);
        }

        @Bean
        AiSimulationWorker worker(AiSimulationPersistenceService persistence,
            AiSimulationAiClient client, AiSimulationResponseValidator validator) {
            return new AiSimulationWorker(persistence, client, validator);
        }
    }
}
