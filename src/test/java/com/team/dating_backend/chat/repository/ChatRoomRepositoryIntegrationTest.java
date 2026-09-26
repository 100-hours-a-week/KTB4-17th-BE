package com.team.dating_backend.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@Testcontainers(disabledWithoutDocker = true)
class ChatRoomRepositoryIntegrationTest {

    private static final Long VIEWER_USER_ID = 10L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 26, 10, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("delete from chat_messages");
        jdbcTemplate.update("delete from chat_participants");
        jdbcTemplate.update("delete from chat_rooms");
    }

    @Test
    void 활성_조회자에게_보이는_채팅방만_반환한다() {
        insertRoom(101L, "ACTIVE", BASE_TIME.plusMinutes(1));
        insertParticipants(101L, VIEWER_USER_ID, "ACTIVE", 20L);
        insertRoom(102L, "ENDED", BASE_TIME.plusMinutes(2));
        insertParticipants(102L, VIEWER_USER_ID, "ACTIVE", 21L);
        insertRoom(103L, "INACTIVE", BASE_TIME.plusMinutes(3));
        insertParticipants(103L, VIEWER_USER_ID, "ACTIVE", 22L);
        insertRoom(104L, "ACTIVE", BASE_TIME.plusMinutes(4));
        insertParticipants(104L, VIEWER_USER_ID, "LEFT", 23L);
        insertRoom(105L, "ACTIVE", BASE_TIME.plusMinutes(5));
        insertParticipants(105L, 30L, "ACTIVE", 31L);

        List<ChatRoomListRow> result = findRooms(null, null, 10);

        assertThat(result).extracting(ChatRoomListRow::chatRoomId).containsExactly(102L, 101L);
    }

    @Test
    void 마지막_SENT_TEXT_메시지로_가장_큰_ID를_사용한다() {
        insertRoom(201L, "ACTIVE", BASE_TIME);
        insertParticipants(201L, VIEWER_USER_ID, "ACTIVE", 20L);
        Long viewerParticipantId = participantId(201L, VIEWER_USER_ID);
        Long otherParticipantId = participantId(201L, 20L);
        insertMessage(1001L, 201L, viewerParticipantId, "TEXT", "old text", "SENT", BASE_TIME.plusMinutes(1), null,
            null);
        insertMessage(1002L, 201L, otherParticipantId, "IMAGE", null, "SENT", BASE_TIME.plusMinutes(2), null, null);
        insertMessage(1003L, 201L, otherParticipantId, "TEXT", "failed text", "FAILED", BASE_TIME.plusMinutes(3), null,
            null);
        insertMessage(
            1004L,
            201L,
            otherParticipantId,
            "TEXT",
            "latest visible text",
            "SENT",
            BASE_TIME.plusMinutes(4),
            null,
            BASE_TIME.plusMinutes(5));

        ChatRoomListRow result = findRooms(null, null, 10).getFirst();

        assertThat(result.lastMessageId()).isEqualTo(1004L);
        assertThat(result.lastMessageSenderParticipantId()).isEqualTo(otherParticipantId);
        assertThat(result.lastMessageTextContent()).isEqualTo("latest visible text");
        assertThat(result.lastMessageReceiverDeletedAt()).isEqualTo(BASE_TIME.plusMinutes(5));
        assertThat(result.activityAt()).isEqualTo(BASE_TIME.plusMinutes(4));
    }

    @Test
    void 마지막_메시지_시각과_방_ID로_정렬하고_메시지가_없으면_방_생성_시각을_사용한다() {
        insertRoom(301L, "ACTIVE", BASE_TIME.plusMinutes(1));
        insertParticipants(301L, VIEWER_USER_ID, "ACTIVE", 20L);
        insertRoom(302L, "ACTIVE", BASE_TIME.plusMinutes(2));
        insertParticipants(302L, VIEWER_USER_ID, "ACTIVE", 21L);
        insertRoom(303L, "ACTIVE", BASE_TIME.plusMinutes(1));
        insertParticipants(303L, VIEWER_USER_ID, "ACTIVE", 22L);
        Long senderParticipantId = participantId(301L, 20L);
        insertMessage(1101L, 301L, senderParticipantId, "TEXT", "message", "SENT", BASE_TIME.plusMinutes(3), null,
            null);

        List<ChatRoomListRow> result = findRooms(null, null, 10);

        assertThat(result).extracting(ChatRoomListRow::chatRoomId).containsExactly(301L, 302L, 303L);
        assertThat(result.get(1).lastMessageId()).isNull();
        assertThat(result.get(1).activityAt()).isEqualTo(BASE_TIME.plusMinutes(2));
    }

    @Test
    void 복합_커서를_배타적으로_적용한다() {
        LocalDateTime tiedActivityAt = BASE_TIME.plusMinutes(3);
        insertRoom(401L, "ACTIVE", BASE_TIME);
        insertParticipants(401L, VIEWER_USER_ID, "ACTIVE", 20L);
        insertRoom(402L, "ACTIVE", tiedActivityAt);
        insertParticipants(402L, VIEWER_USER_ID, "ACTIVE", 21L);
        insertRoom(403L, "ACTIVE", tiedActivityAt);
        insertParticipants(403L, VIEWER_USER_ID, "ACTIVE", 22L);
        insertRoom(404L, "ACTIVE", BASE_TIME.plusMinutes(4));
        insertParticipants(404L, VIEWER_USER_ID, "ACTIVE", 23L);

        List<ChatRoomListRow> result = findRooms(tiedActivityAt, 403L, 10);

        assertThat(result).extracting(ChatRoomListRow::chatRoomId).containsExactly(402L, 401L);
    }

    @Test
    void 커서_앞으로_이동한_방은_목록을_새로_조회할_때까지_다음_페이지에서_제외된다() {
        insertRoom(501L, "ACTIVE", BASE_TIME.plusMinutes(4));
        insertParticipants(501L, VIEWER_USER_ID, "ACTIVE", 20L);
        insertRoom(502L, "ACTIVE", BASE_TIME.plusMinutes(3));
        insertParticipants(502L, VIEWER_USER_ID, "ACTIVE", 21L);
        insertRoom(503L, "ACTIVE", BASE_TIME.plusMinutes(2));
        insertParticipants(503L, VIEWER_USER_ID, "ACTIVE", 22L);
        insertRoom(504L, "ACTIVE", BASE_TIME.plusMinutes(1));
        insertParticipants(504L, VIEWER_USER_ID, "ACTIVE", 23L);
        List<ChatRoomListRow> firstPage = findRooms(null, null, 2);
        ChatRoomListRow cursorRow = firstPage.getLast();
        Long movedRoomSenderId = participantId(503L, 22L);

        insertMessage(
            1201L,
            503L,
            movedRoomSenderId,
            "TEXT",
            "new message",
            "SENT",
            BASE_TIME.plusMinutes(5),
            null,
            null);

        List<ChatRoomListRow> secondPage = findRooms(cursorRow.activityAt(), cursorRow.chatRoomId(), 2);
        List<ChatRoomListRow> refreshedFirstPage = findRooms(null, null, 2);

        assertThat(secondPage).extracting(ChatRoomListRow::chatRoomId).containsExactly(504L);
        assertThat(refreshedFirstPage).extracting(ChatRoomListRow::chatRoomId).containsExactly(503L, 501L);
    }

    private List<ChatRoomListRow> findRooms(LocalDateTime cursorActivityAt, Long cursorRoomId, int size) {
        return chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID, cursorActivityAt, cursorRoomId, PageRequest.of(0, size));
    }

    private void insertRoom(Long roomId, String status, LocalDateTime createdAt) {
        jdbcTemplate.update(
            "insert into chat_rooms (id, match_id, status, created_at) values (?, ?, ?, ?)",
            roomId,
            roomId + 10000,
            status,
            Timestamp.valueOf(createdAt));
    }

    private void insertParticipants(Long roomId, Long viewerUserId, String viewerStatus, Long otherUserId) {
        jdbcTemplate.update(
            "insert into chat_participants (chat_room_id, user_id, status, is_chat_notification) values (?, ?, ?, ?)",
            roomId,
            viewerUserId,
            viewerStatus,
            true);
        jdbcTemplate.update(
            "insert into chat_participants (chat_room_id, user_id, status, is_chat_notification) values (?, ?, ?, ?)",
            roomId,
            otherUserId,
            "ACTIVE",
            true);
    }

    private Long participantId(Long roomId, Long userId) {
        return jdbcTemplate.queryForObject(
            "select id from chat_participants where chat_room_id = ? and user_id = ?", Long.class, roomId, userId);
    }

    private void insertMessage(
        Long messageId,
        Long roomId,
        Long senderParticipantId,
        String messageType,
        String textContent,
        String status,
        LocalDateTime createdAt,
        LocalDateTime senderDeletedAt,
        LocalDateTime receiverDeletedAt) {
        jdbcTemplate.update(
            "insert into chat_messages (id, chat_room_id, sender_id, message_type, text_content, status, created_at, sender_deleted_at, receiver_deleted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            messageId,
            roomId,
            senderParticipantId,
            messageType,
            textContent,
            status,
            Timestamp.valueOf(createdAt),
            senderDeletedAt == null ? null : Timestamp.valueOf(senderDeletedAt),
            receiverDeletedAt == null ? null : Timestamp.valueOf(receiverDeletedAt));
    }
}
