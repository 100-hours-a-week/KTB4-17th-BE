package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {AiSimulationPersistenceService.class, AiSimulationGenerationService.class,
        AiSimulationResponseValidator.class, AiSimulationEventPublisher.class, AiSimulationEventFactory.class,
        AiSimulationGenerationServiceIntegrationTest.MappingConfiguration.class}
)
@Testcontainers
@Timeout(30)
class AiSimulationGenerationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationGenerationService service;

    @Autowired
    private AiSimulationPersistenceService persistence;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AiSimulationAiClient ai;

    @MockitoBean
    private KafkaTemplate<Object, Object> template;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return aiResponse();
        });
        when(template.send(anyString(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long id = invocation.getArgument(1);
            assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
            assertThat(messageCount(id)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, id)).isEqualTo("COMPLETED");
            return CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void AI_호출은_트랜잭션_밖에서_수행하고_결과_커밋_후_완료_이벤트를_발행한다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();

        service.generate(id);

        verify(ai).run(new SimulationRequest(
            USER_ID.toString(), TARGET_ID.toString(), 10));
        var event = ArgumentCaptor.forClass(AiSimulationCompletedEvent.class);
        verify(template).send(anyString(), eq(id), event.capture());
        var state = persistence.loadGenerationState(id);
        assertThat(event.getValue()).isEqualTo(new AiSimulationEventFactory().completed(id, state.finishedAt()));
    }

    @Test
    void 완료_발행_실패_후_재처리하면_AI_재호출없이_동일한_이벤트를_재발행한다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        doReturn(CompletableFuture.failedFuture(new TimeoutException()))
            .doReturn(CompletableFuture.completedFuture(null))
            .when(template).send(anyString(), eq(id), any());

        assertThatThrownBy(() -> service.generate(id))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED));
        var committed = persistence.loadGenerationState(id);
        assertThat(committed.status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);

        service.generate(id);

        verify(ai, times(1)).run(any());
        assertThat(persistence.loadGenerationState(id)).isEqualTo(committed);
        assertThat(messageCount(id)).isEqualTo(20);
        var events = ArgumentCaptor.forClass(AiSimulationCompletedEvent.class);
        verify(template, times(2)).send(anyString(), eq(id), events.capture());
        assertThat(events.getAllValues()).containsOnly(
            new AiSimulationEventFactory().completed(id, committed.finishedAt()));
    }

    @Test
    void 결과가_커밋되어도_완료_발행_성공을_확인할_때까지_반환하지_않는다() throws Exception {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        var sent = new CountDownLatch(1);
        var acknowledgement = new CompletableFuture<SendResult<Object, Object>>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
            sent.countDown();
            return acknowledgement;
        }).when(template).send(anyString(), eq(id), any());
        try (var calls = Executors.newSingleThreadExecutor()) {
            var result = calls.submit(() -> service.generate(id));
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                acknowledgement.complete(null);
            }
            result.get(10, TimeUnit.SECONDS);
        }
        assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
    }

    @Test
    void 잘못된_AI_응답은_호출자에게_전달하고_진행중_상태를_유지한다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        when(ai.run(any())).thenReturn(null);

        assertThatThrownBy(() -> service.generate(id))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID));

        assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(messageCount(id)).isZero();
        verifyNoInteractions(template);
    }

    @Test
    void 결과_커밋_실패는_진행중_상태로_롤백하고_완료_이벤트를_발행하지_않는다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        jdbc.execute(
            "ALTER TABLE ai_simulation_reports ADD CONSTRAINT reject_generation_commit CHECK (status <> 'COMPLETED')");
        try {
            assertThatThrownBy(() -> service.generate(id)).isInstanceOf(DataAccessException.class);
            assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
            assertThat(messageCount(id)).isZero();
            verifyNoInteractions(template);
        } finally {
            jdbc.execute("ALTER TABLE ai_simulation_reports DROP CHECK reject_generation_commit");
        }
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void AI_응답_대기_중_종료된_세션은_결과_저장과_완료_발행을_생략한다(AiSimulationSessionStatus status) {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE ai_simulation_sessions SET status=?, finished_at=UTC_TIMESTAMP(6) WHERE id=?",
                status.name(), id);
            return aiResponse();
        });

        service.generate(id);

        assertThat(persistence.loadGenerationState(id).status()).isEqualTo(status);
        assertThat(messageCount(id)).isZero();
        verifyNoInteractions(template);
    }

    @Test
    void AI_호출_중_다른_처리가_완료했다면_그_완료_결과로_발행한다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();
        when(ai.run(any())).thenAnswer(invocation -> {
            persistence.completeGeneration(id, aiResponse());
            return aiResponse();
        });

        service.generate(id);

        assertThat(messageCount(id)).isEqualTo(20);
        var events = ArgumentCaptor.forClass(AiSimulationCompletedEvent.class);
        verify(template).send(anyString(), eq(id), events.capture());
        assertThat(events.getValue()).isEqualTo(new AiSimulationEventFactory().completed(
            id, persistence.loadGenerationState(id).finishedAt()));
    }

    @Test
    void 바깥_트랜잭션이_있으면_AI_호출_전에_거부한다() {
        Long id = persistence.begin(USER_ID, TARGET_ID).getId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> service.generate(id)))
            .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(ai, template);
        assertThat(persistence.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
    }

    private int messageCount(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?", Integer.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class MappingConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
