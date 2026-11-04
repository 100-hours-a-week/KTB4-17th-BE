package com.team.dating_backend.aisimulation.support;

import com.team.dating_backend.aisimulation.AiSimulationTestFixtures;
import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationAsyncConfig;
import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.service.AiSimulationAdmissionService;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationRecoveryJob;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.aisimulation.service.AiSimulationTargetValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationWorker;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * Test-only process: uses production services and a controllable AI boundary,
 * never production credentials.
 */
public final class AiSimulationFaultProbe {
    private AiSimulationFaultProbe() {}

    public static void main(String[] args) throws Exception {
        var application = new SpringApplication(Config.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run(args);
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var json = context.getBean(ObjectMapper.class);
            System.out.println("PROBE_READY");
            String line;
            while ((line = input.readLine()) != null) {
                String[] command = line.split(" ");
                Object result;
                switch (command[0]) {
                    case "request" -> result = context.getBean(AiSimulationService.class).request(
                        Long.valueOf(command[1]), new AiSimulationCreateRequest(Long.valueOf(command[2])));
                    case "recover" -> {
                        context.getBean(AiSimulationRecoveryJob.class).recoverExpired();
                        result = Map.of("recovered", true);
                    }
                    case "stats" -> {
                        var executor = context.getBean("aiSimulationExecutor", ThreadPoolTaskExecutor.class);
                        result = Map.of("aiCalls", context.getBean(ControlledAi.class).calls.size(),
                            "active", executor.getActiveCount(), "queued", executor.getQueueSize());
                    }
                    default -> throw new IllegalArgumentException("Unknown test command");
                }
                System.out.println("PROBE_REPLY " + json.writeValueAsString(result));
                System.out.flush();
            }
        }
    }

    public static final class ControlledAi implements AiSimulationAiClient {
        private final List<SimulationRequest> calls = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch release = new CountDownLatch(0);

        public void block() {
            calls.clear();
            release = new CountDownLatch(1);
        }

        public void reset() {
            release.countDown();
            calls.clear();
            release = new CountDownLatch(0);
        }

        public void release() {
            release.countDown();
        }

        public int callCount() {
            return calls.size();
        }

        @Override
        public SimulationResponse run(SimulationRequest request) {
            calls.add(request);
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test AI release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test AI interrupted", exception);
            }
            var response = AiSimulationTestFixtures.aiResponse();
            var me = response.me();
            var partner = response.partner();
            return new SimulationResponse(response.simulationId(),
                new PersonaBrief(me.personaId(), request.meUserId(), me.nickname(), me.version(), me.headline(),
                    me.accuracy()),
                new PersonaBrief(partner.personaId(), request.partnerUserId(), partner.nickname(), partner.version(),
                    partner.headline(), partner.accuracy()),
                response.turns(), response.transcript(), response.report(), response.createdAt());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.team.dating_backend")
    @EnableJpaRepositories("com.team.dating_backend")
    @EnableConfigurationProperties(AiSimulationProperties.class)
    @Import(
        {AiSimulationAsyncConfig.class, AiSimulationService.class, AiSimulationAdmissionService.class,
            AiSimulationWorker.class, AiSimulationPersistenceService.class, AiSimulationResponseValidator.class,
            AiSimulationRecoveryJob.class, AiSimulationNotificationService.class, AiSimulationTargetValidator.class,
            SimulationRecommendationService.class, RecommendationBatchCreateService.class, UserPairLockRepository.class,
            NotificationRequestService.class, NotificationRequestOutboxService.class,
            NotificationRequestOutboxCodec.class, NotificationCreateRequestedValidator.class}
    )
    public static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ControlledAi controlledAi(org.springframework.core.env.Environment environment) {
            var client = new ControlledAi();
            if (environment.getProperty("probe.block-ai", Boolean.class, false)) {
                client.block();
            }
            return client;
        }
    }
}
