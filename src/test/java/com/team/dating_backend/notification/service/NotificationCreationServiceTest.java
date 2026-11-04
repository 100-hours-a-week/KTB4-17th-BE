package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.enums.NotificationType;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

class NotificationCreationServiceTest {

    private static final NotificationCreateRequested REQUEST = new NotificationCreateRequested(
        1, "event-1", 7L, NotificationType.LIKE, "재처리 제목", "재처리 본문", null, null,
        Instant.parse("2026-10-21T01:00:00Z"));
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 1, 0);
    private static final NotificationCreationResult RESULT = new NotificationCreationResult(
        "event-1", 7L, new NotificationEventResponse(
            10L, "LIKE", "원본 제목", "원본 본문", "USER", 2L, CREATED_AT.plusMinutes(1), CREATED_AT),
        CREATED_AT.plusMinutes(2));

    private NotificationPersistenceService persistenceService;
    private NotificationLookupService lookupService;
    private NotificationCreationService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(NotificationPersistenceService.class);
        lookupService = mock(NotificationLookupService.class);
        service = new NotificationCreationService(persistenceService, lookupService);
    }

    @Test
    void 신규_저장_결과를_반환하고_기존_행을_미리_조회하지_않는다() {
        given(persistenceService.create(REQUEST)).willReturn(RESULT);

        assertThat(service.createOrLoad(REQUEST)).isSameAs(RESULT);

        verify(persistenceService).create(REQUEST);
        verifyNoInteractions(lookupService);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"uk_notifications_source_recipient_type",
            "notifications.uk_notifications_source_recipient_type"}
    )
    void 알림_중복_제약만_조회로_연결하고_기존_내용과_상태를_그대로_반환한다(String constraintName) {
        given(persistenceService.create(REQUEST)).willThrow(constraintFailure(constraintName));
        given(lookupService.findExisting(REQUEST)).willReturn(Optional.of(RESULT));

        assertThat(service.createOrLoad(REQUEST)).isSameAs(RESULT);

        verify(persistenceService).create(REQUEST);
        verify(lookupService).findExisting(REQUEST);
    }

    @ParameterizedTest
    @MethodSource("unrecoverableFailures")
    void 다른_제약과_저장_오류는_조회하지_않고_원래_예외를_전달한다(RuntimeException failure) {
        given(persistenceService.create(REQUEST)).willThrow(failure);

        assertThatThrownBy(() -> service.createOrLoad(REQUEST)).isSameAs(failure);

        verifyNoInteractions(lookupService);
    }

    @Test
    void 중복_오류인데_기존_행이_없으면_최초_저장_예외를_전달한다() {
        DataIntegrityViolationException failure = constraintFailure("uk_notifications_source_recipient_type");
        given(persistenceService.create(REQUEST)).willThrow(failure);
        given(lookupService.findExisting(REQUEST)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrLoad(REQUEST)).isSameAs(failure);
    }

    @Test
    void 중복_조회에_실패하면_성공으로_처리하지_않고_조회_예외를_전달한다() {
        given(persistenceService.create(REQUEST))
            .willThrow(constraintFailure("uk_notifications_source_recipient_type"));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("조회 실패");
        given(lookupService.findExisting(REQUEST)).willThrow(failure);

        assertThatThrownBy(() -> service.createOrLoad(REQUEST)).isSameAs(failure);
    }

    private static Stream<RuntimeException> unrecoverableFailures() {
        return Stream.of(
            constraintFailure("fk_notifications_user"),
            constraintFailure("uk_other"),
            constraintFailure("prefix_uk_notifications_source_recipient_type"),
            constraintFailure(null),
            new DataIntegrityViolationException("제약 정보 없음"),
            new DataAccessResourceFailureException("저장 연결 실패"),
            new TransactionSystemException("커밋 실패"));
    }

    private static DataIntegrityViolationException constraintFailure(String constraintName) {
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
            "제약 위반", new SQLException(), ConstraintViolationException.ConstraintKind.UNIQUE, constraintName);
        return new DataIntegrityViolationException("무결성 위반", new IllegalStateException(constraintViolation));
    }
}
