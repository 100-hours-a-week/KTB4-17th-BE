package com.team.dating_backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0, 0, 123456789);

    @Test
    void 새_알림은_미읽음이고_삭제되지_않은_상태다() {
        Notification notification = notification();

        assertThat(notification.getReadAt()).isNull();
        assertThat(notification.getDeletedAt()).isNull();
        assertThat(notification.getCreatedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(notification.getTargetType()).isNull();
        assertThat(notification.getTargetId()).isNull();
    }

    @Test
    void 반복_읽음은_최초_읽음_시각을_보존한다() {
        Notification notification = notification();

        notification.markAsRead(NOW.plusMinutes(1));
        notification.markAsRead(NOW.plusMinutes(2));

        assertThat(notification.getReadAt())
            .isEqualTo(NOW.plusMinutes(1).truncatedTo(ChronoUnit.MICROS));
        assertThat(notification.getDeletedAt()).isNull();
        assertThat(notification.getCreatedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void 반복_삭제는_최초_삭제_시각과_기존_읽음_시각을_보존한다() {
        Notification notification = notification();
        notification.markAsRead(NOW.plusMinutes(1));

        notification.markAsDeleted(NOW.plusMinutes(2));
        notification.markAsDeleted(NOW.plusMinutes(3));

        assertThat(notification.getDeletedAt())
            .isEqualTo(NOW.plusMinutes(2).truncatedTo(ChronoUnit.MICROS));
        assertThat(notification.getReadAt())
            .isEqualTo(NOW.plusMinutes(1).truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void 미읽음_알림을_삭제해도_읽음으로_바뀌지_않는다() {
        Notification notification = notification();

        notification.markAsDeleted(NOW);

        assertThat(notification.getReadAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("missingRequiredValues")
    void 필수값이_없으면_서버_오류_코드로_실패한다(
        User user, NotificationType type, String title, String body, LocalDateTime createdAt) {
        assertInternalError(() -> new Notification(user, type, title, body, null, null, createdAt));
    }

    @ParameterizedTest
    @MethodSource("invalidTargets")
    void 관련_대상이_불완전하거나_유효하지_않으면_서버_오류_코드로_실패한다(
        String targetType, Long targetId) {
        assertInternalError(() -> new Notification(user(), NotificationType.LIKE,
            "관심", "관심 도착", targetType, targetId, NOW));
    }

    @ParameterizedTest
    @MethodSource("oversizedTexts")
    void 최대_길이를_초과한_문자열은_서버_오류_코드로_실패한다(
        String title, String body, String targetType) {
        assertInternalError(() -> new Notification(user(), NotificationType.NOTICE,
            title, body, targetType, 1L, NOW));
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void 모든_업무_유형은_ALL과_자신의_카테고리에_포함된다(NotificationType type) {
        NotificationCategory expectedCategory = switch (type) {
            case LIKE, MATCH -> NotificationCategory.INTEREST;
            case CHAT_MESSAGE -> NotificationCategory.CHAT;
            case PERSONA_TRAINING_COMPLETED, FACE_ANALYSIS_COMPLETED,
                AI_PRACTICE_CHAT_COMPLETED, AI_SIMULATION_REPORT_COMPLETED,
                NOTICE -> NotificationCategory.SYSTEM;
        };

        assertThat(type.getCategory()).isEqualTo(expectedCategory);
        assertThat(NotificationCategory.ALL.getTypes()).contains(type);
        for (NotificationCategory category : NotificationCategory.values()) {
            if (category == NotificationCategory.ALL || category == expectedCategory) {
                assertThat(category.getTypes()).contains(type);
            } else {
                assertThat(category.getTypes()).doesNotContain(type);
            }
        }
    }

    private static Stream<Arguments> missingRequiredValues() {
        return Stream.of(
            Arguments.of(null, NotificationType.LIKE, "관심", "관심 도착", NOW),
            Arguments.of(user(), null, "관심", "관심 도착", NOW),
            Arguments.of(user(), NotificationType.LIKE, null, "관심 도착", NOW),
            Arguments.of(user(), NotificationType.LIKE, "관심", null, NOW),
            Arguments.of(user(), NotificationType.LIKE, "관심", "관심 도착", null));
    }

    private static Stream<Arguments> invalidTargets() {
        return Stream.of(
            Arguments.of("PROFILE", null),
            Arguments.of(null, 1L),
            Arguments.of(" ", 1L),
            Arguments.of("PROFILE", 0L));
    }

    private static Stream<Arguments> oversizedTexts() {
        return Stream.of(
            Arguments.of("😀".repeat(101), "본문", "PROFILE"),
            Arguments.of("제목", "😀".repeat(501), "PROFILE"),
            Arguments.of("제목", "본문", "😀".repeat(51)));
    }

    private static Notification notification() {
        return new Notification(user(), NotificationType.LIKE, "관심", "관심 도착", null, null, NOW);
    }

    private static User user() {
        return User.create("테스트", LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
    }

    private static void assertInternalError(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(NotificationBusinessException.class,
            exception -> {
                assertThat(exception.getErrorCode())
                    .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
                assertThat(exception.getMessage()).isNull();
            });
    }
}
