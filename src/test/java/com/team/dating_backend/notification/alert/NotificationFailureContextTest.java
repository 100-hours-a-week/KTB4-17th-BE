package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.alert.NotificationFailureContext.FailureStage;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationFailureContextTest {

    @Test
    void 기존_생성자와_factory는_Kafka_문맥_없이_같은_결과를_만든다() {
        Instant now = Instant.now();
        var context = NotificationFailureContext.from(NotificationFailureCode.PUBLISH_CALL_FAILED, null, null, now);
        assertThat(context).isEqualTo(new NotificationFailureContext(NotificationFailureCode.PUBLISH_CALL_FAILED,
            null, null, null, null, now, null, null));
        assertThat(context.kafkaDetails()).isNull();
    }

    @ParameterizedTest
    @CsvSource(
        {"RETRY_PUBLISH_FAILED,RETRY_PUBLISH", "DLT_PUBLISH_FAILED,DLT_PUBLISH", "DLT_RECORDED,CREATION_PROCESSING"}
    )
    void 실패_코드에서_단계를_결정한다(NotificationFailureCode code, FailureStage stage) {
        assertThat(NotificationFailureContext.from(code, null, null, Instant.now()).failureStage()).isEqualTo(stage);
    }

    @Test
    void 미확인_위치와_분류를_허용한다() {
        var details = KafkaFailureDetails.from(null, null, null, new KafkaLocation("create-dlt", null, null), null,
            null, null);
        assertThat(details.destinationLocation().offset()).isNull();
        assertThat(details.retryable()).isNull();
        assertThat(details.processingExceptionType()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1"})
    void 음수_위치는_errorCode_예외로_거부한다(int partition, long offset) {
        assertThatThrownBy(() -> new KafkaLocation("create", partition, offset))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(com.team.dating_backend.common.enums.CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    @Test
    void partition_없는_offset을_거부한다() {
        assertThatThrownBy(() -> new KafkaLocation("create", null, 1L))
            .isInstanceOf(NotificationBusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 잘못된_시도_횟수를_거부한다(int attempt) {
        assertThatThrownBy(() -> KafkaFailureDetails.from(null, null, null, null, attempt, null, null))
            .isInstanceOf(NotificationBusinessException.class);
    }
}
