package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import com.team.dating_backend.user.enums.UserStatus;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("notification-multiprocess")
@Timeout(90)
class NotificationMultiProcessApiIntegrationTest extends NotificationMultiProcessTestSupport {

    @ParameterizedTest
    @ValueSource(
        strings = {"missing", "malformed", "expired", "wrong-signature", "unknown-user", "withdrawn", "onboarding",
            "suspended"}
    )
    void 실제_Filter와_회원_검증으로_5개_알림_API의_401과_403을_구분한다(String condition) throws Exception {
        String accessToken = switch (condition) {
            case "missing" -> null;
            case "malformed" -> "invalid-token";
            case "expired" -> token(activeUser(), environment.jwtSecret(), -1);
            case "wrong-signature" -> {
                var key = new byte[64];
                new SecureRandom().nextBytes(key);
                yield token(activeUser(), Base64.getEncoder().encodeToString(key), 60);
            }
            case "unknown-user" -> token(Long.MAX_VALUE);
            case "withdrawn" -> token(user(UserStatus.WITHDRAWN));
            case "onboarding" -> token(user(UserStatus.ONBOARDING));
            case "suspended" -> token(user(UserStatus.SUSPENDED));
            default -> throw new AssertionError();
        };
        boolean authenticated = condition.equals("onboarding") || condition.equals("suspended");
        int status = authenticated ? 403 : 401;
        String code = authenticated ? "REQUESTER_NOT_ACTIVE" : "AUTH_REQUIRED";
        var results = new ArrayList<Map<String, Object>>();
        for (var endpoint : List.of(
            new Endpoint("GET", NOTIFICATIONS), new Endpoint("GET", NOTIFICATIONS + "/unread-count"),
            new Endpoint("GET", NOTIFICATIONS + "/stream"), new Endpoint("PUT", NOTIFICATIONS + "/1/read-receipt"),
            new Endpoint("DELETE", NOTIFICATIONS + "/1"))) {
            var response = request(second, endpoint.method(), endpoint.path(), accessToken, null);
            error(response, status, code);
            results.add(Map.of("method", endpoint.method(), "path", endpoint.path(),
                "status", response.statusCode(), "errorCode",
                JSON.readTree(response.body()).path("errorCode").asText()));
        }
        environment.report("auth-" + condition, Map.of("condition", condition, "responses", results));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "cursor=bad, INVALID_NOTIFICATION_CURSOR",
            "category=UNKNOWN, INVALID_NOTIFICATION_CATEGORY",
            "size=0, INVALID_NOTIFICATION_PAGE_SIZE"
        }
    )
    void 실제_인증_이후에도_목록_입력_오류는_errorCode만_반환한다(String query, String code) throws Exception {
        error(request(first, "GET", NOTIFICATIONS + "?" + query, token(activeUser()), null), 400, code);
    }

    @Test
    void 양쪽_서버의_문자열_cursor_탐색과_읽음_삭제가_동일한_DB_상태를_유지한다() throws Exception {
        long recipient = activeUser();
        long unrelated = activeUser();
        String accessToken = token(recipient);
        var expected = new ArrayList<Expected>();
        for (int index = 0; index < 5; index++) {
            expected.add(likeNotification(index % 2 == 0 ? first : second, activeUser(), recipient));
        }
        var other = likeNotification(first, activeUser(), unrelated);
        stored(other);
        var expectedIds = expected.stream().map(key -> stored(key).notificationId()).toList();
        environment.awaitProcessed();
        var before = jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? ORDER BY id", recipient);
        var orderedIds = jdbc.queryForList(
            "SELECT id FROM notifications WHERE user_id = ? ORDER BY created_at DESC, id DESC",
            Long.class, recipient);
        var visited = new ArrayList<Long>();
        String cursor = null;
        int pageCount = 0;
        do {
            String query = "?category=INTEREST&size=2" + (cursor == null ? "" : "&cursor=" + cursor);
            var page = success(
                request(pageCount % 2 == 0 ? first : second, "GET", NOTIFICATIONS + query, accessToken, null), 200);
            page.path("items").forEach(item -> visited.add(item.path("notificationId").asLong()));
            pageCount++;
            assertThat(pageCount).isLessThanOrEqualTo(3);
            if (page.path("pageInfo").path("hasNext").asBoolean()) {
                var next = page.path("pageInfo").path("nextCursor");
                assertThat(next.isString()).isTrue();
                cursor = next.asText();
                assertThat(cursor).isNotBlank();
            } else {
                assertThat(page.path("pageInfo").path("nextCursor").isNull()).isTrue();
                cursor = null;
            }
        } while (cursor != null);
        assertThat(pageCount).isEqualTo(3);
        assertThat(visited).containsExactlyElementsOf(orderedIds).doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? ORDER BY id", recipient))
            .isEqualTo(before);
        assertThat(
            success(request(second, "GET", NOTIFICATIONS + "?category=CHAT", accessToken, null), 200).path("items"))
            .isEmpty();
        assertUnread(recipient, 5);

        long readId = orderedIds.getFirst();
        noContent(request(first, "PUT", NOTIFICATIONS + "/" + readId + "/read-receipt", accessToken, null));
        var read = row(readId);
        assertThat(read.get("read_at")).isNotNull();
        noContent(request(second, "PUT", NOTIFICATIONS + "/" + readId + "/read-receipt", accessToken, null));
        assertThat(row(readId)).isEqualTo(read);
        assertUnread(recipient, 4);
        noContent(request(second, "DELETE", NOTIFICATIONS + "/" + readId, accessToken, null));
        var deletedRead = row(readId);
        assertThat(deletedRead.get("read_at")).isEqualTo(read.get("read_at"));
        assertThat(deletedRead.get("deleted_at")).isNotNull();
        noContent(request(first, "DELETE", NOTIFICATIONS + "/" + readId, accessToken, null));
        assertThat(row(readId)).isEqualTo(deletedRead);
        error(request(first, "PUT", NOTIFICATIONS + "/" + readId + "/read-receipt", accessToken, null),
            404, "NOTIFICATION_NOT_FOUND");
        assertUnread(recipient, 4);

        long unreadId = orderedIds.get(1);
        noContent(request(first, "DELETE", NOTIFICATIONS + "/" + unreadId, accessToken, null));
        assertThat(row(unreadId).get("read_at")).isNull();
        assertUnread(recipient, 3);
        var visible = success(request(second, "GET", NOTIFICATIONS, accessToken, null), 200).path("items");
        var visibleIds = new ArrayList<Long>();
        visible.forEach(item -> visibleIds.add(item.path("notificationId").asLong()));
        assertThat(visibleIds).containsExactlyElementsOf(orderedIds.subList(2, 5));
        environment.report("api-state-and-cursor", Map.of("userId", recipient, "orderedIds", orderedIds,
            "visitedIds", visited, "pages", pageCount, "readAndDeletedId", readId,
            "deletedUnreadId", unreadId, "visibleIds", visibleIds, "unreadCount", 3));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 다른_회원의_삭제_상태에_따라_읽음_404와_접근_거부_403을_구분한다(boolean deleted) throws Exception {
        long owner = activeUser();
        long other = activeUser();
        long notificationId = stored(likeNotification(first, activeUser(), owner)).notificationId();
        if (deleted) {
            noContent(request(first, "DELETE", NOTIFICATIONS + "/" + notificationId, token(owner), null));
        }
        var before = row(notificationId);

        error(request(second, "PUT", NOTIFICATIONS + "/" + notificationId + "/read-receipt", token(other), null),
            deleted ? 404 : 403, deleted ? "NOTIFICATION_NOT_FOUND" : "NOTIFICATION_ACCESS_DENIED");
        error(request(second, "DELETE", NOTIFICATIONS + "/" + notificationId, token(other), null),
            403, "NOTIFICATION_ACCESS_DENIED");

        assertThat(row(notificationId)).isEqualTo(before);
    }

    @Test
    void 없는_알림은_읽음_404와_삭제_204를_반환하고_행을_만들지_않는다() throws Exception {
        String accessToken = token(activeUser());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class);

        error(request(first, "PUT", NOTIFICATIONS + "/" + Long.MAX_VALUE + "/read-receipt", accessToken, null),
            404, "NOTIFICATION_NOT_FOUND");
        noContent(request(second, "DELETE", NOTIFICATIONS + "/" + Long.MAX_VALUE, accessToken, null));
        noContent(request(first, "DELETE", NOTIFICATIONS + "/" + Long.MAX_VALUE, accessToken, null));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(before);
    }

    private void assertUnread(long userId, long expected) throws Exception {
        var count = success(request(second, "GET", NOTIFICATIONS + "/unread-count", token(userId), null), 200);
        assertThat(count.path("count").asLong()).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL AND deleted_at IS NULL",
            Long.class, userId)).isEqualTo(expected);
    }

    private Map<String, Object> row(long notificationId) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", notificationId);
    }

    private void noContent(java.net.http.HttpResponse<String> response) {
        assertThat(response.statusCode()).as("response body=%s", response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    private record Endpoint(String method, String path) {}
}
