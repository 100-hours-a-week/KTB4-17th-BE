package com.team.dating_backend.chat.delegation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.chat.delegation.config.ChatDelegationProperties;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.ConfirmResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestChatDelegationAiClientTest {

    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private MockRestServiceServer server;
    private ChatDelegationProperties properties;
    private RestChatDelegationAiClient client;
    private DraftRequest request;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new ChatDelegationProperties();
        properties.setBaseUrl("http://ai.internal");
        properties.setApiKey("secret");
        client = new RestChatDelegationAiClient(builder.build(), properties);
        request = new DraftRequest(REQUEST_ID, 10L, 20L, "30",
            ChatDelegationEndType.GENTLE,
            List.of(new RecentMessage(Speaker.REQUESTER, "안녕하세요")));
    }

    @Test
    void 인증키와_멱등성키를_포함해_초안을_요청한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/chat_end/drafts"))
            .andExpect(header("X-Internal-Api-Key", "secret"))
            .andExpect(content().json("""
                {"request_id":"11111111-1111-1111-1111-111111111111","room_id":10,
                "delegation_id":20,"requester_user_id":"30","end_type":"GENTLE",
                "recent_messages":[{"speaker":"REQUESTER","content":"안녕하세요"}]}
                """))
            .andRespond(withSuccess(validResponse(), MediaType.APPLICATION_JSON));

        DraftResponse response = client.createDraft(request);

        assertThat(response.draftId()).isEqualTo("a".repeat(32));
        server.verify();
    }

    @Test
    void 인증키가_없으면_AI를_호출하지_않는다() {
        properties.setApiKey("");
        assertError(() -> client.createDraft(request), ChatErrorCode.CHAT_DELEGATION_AI_NOT_CONFIGURED);
        server.verify();
    }

    @Test
    void 인증키를_포함해_AI_초안을_확정한다() {
        String draftId = "a".repeat(32);
        server.expect(requestTo(
            "http://ai.internal/ai/api/v1/chat_end/drafts/%s/confirm".formatted(draftId)))
            .andExpect(header("X-Internal-Api-Key", "secret"))
            .andRespond(withSuccess("""
                {"draft_id":"%s","delegation_id":20,"status":"CONFIRMED"}
                """.formatted(draftId), MediaType.APPLICATION_JSON));

        ConfirmResponse response = client.confirmDraft(draftId, 20L);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        server.verify();
    }

    @Test
    void AI_서버가_요청과_다른_초안_정보를_반환하면_거부한다() {
        String draftId = "a".repeat(32);
        server.expect(requestTo(
            "http://ai.internal/ai/api/v1/chat_end/drafts/%s/confirm".formatted(draftId)))
            .andRespond(withSuccess("""
                {"draft_id":"%s","delegation_id":21,"status":"CONFIRMED"}
                """.formatted(draftId), MediaType.APPLICATION_JSON));

        assertError(() -> client.confirmDraft(draftId, 20L),
            ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        server.verify();
    }

    @Test
    void AI가_4xx로_응답하면_요청_거절_오류로_변환한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/chat_end/drafts"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertError(() -> client.createDraft(request),
            ChatErrorCode.CHAT_DELEGATION_AI_REQUEST_REJECTED);
        server.verify();
    }

    @Test
    void AI가_5xx로_응답하거나_통신에_실패하면_이용_불가_오류로_변환한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/chat_end/drafts"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertError(() -> client.createDraft(request),
            ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
        server.verify();

        server.reset();
        server.expect(requestTo("http://ai.internal/ai/api/v1/chat_end/drafts"))
            .andRespond(httpRequest -> {
                throw new IOException("connection timed out");
            });
        assertError(() -> client.createDraft(request),
            ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void AI_응답_계약이_다르면_거부한다(String response) {
        server.expect(requestTo("http://ai.internal/ai/api/v1/chat_end/drafts"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertError(() -> client.createDraft(request),
            ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        server.verify();
    }

    private static Stream<Arguments> invalidResponses() {
        String turns = """
            [{"turn":1,"message":"첫 번째"},{"turn":2,"message":"두 번째"},
            {"turn":3,"message":"세 번째"}]
            """;
        return Stream.of(
            Arguments.of("{broken"),
            Arguments.of(responseJson(11, "a".repeat(32), "PENDING", turns)),
            Arguments.of(responseJson(10, "A".repeat(32), "PENDING", turns)),
            Arguments.of(responseJson(10, "a".repeat(32), "CONFIRMED", turns)),
            Arguments.of(responseJson(10, "a".repeat(32), "PENDING",
                "[{\"turn\":1,\"message\":\"하나\"}]")),
            Arguments.of(responseJson(10, "a".repeat(32), "PENDING",
                "[{\"turn\":1,\"message\":\"하나\"},{\"turn\":1,\"message\":\"둘\"},"
                    + "{\"turn\":3,\"message\":\"셋\"}]")),
            Arguments.of(responseJson(10, "a".repeat(32), "PENDING",
                "[{\"turn\":1,\"message\":\" \"},{\"turn\":2,\"message\":\"둘\"},"
                    + "{\"turn\":3,\"message\":\"셋\"}]")),
            Arguments.of(responseJson(10, "a".repeat(32), "PENDING",
                "[{\"turn\":1,\"message\":\"" + "가".repeat(1001)
                    + "\"},{\"turn\":2,\"message\":\"둘\"},"
                    + "{\"turn\":3,\"message\":\"셋\"}]")));
    }

    private static String responseJson(int roomId, String draftId, String status, String turns) {
        return """
            {"room_id":%d,"draft_id":"%s","status":"%s","turns":%s}
            """.formatted(roomId, draftId, status, turns);
    }

    private String validResponse() {
        return """
            {"room_id":10,"draft_id":"%s","status":"PENDING","turns":[
            {"turn":1,"message":"첫 번째"},{"turn":2,"message":"두 번째"},
            {"turn":3,"message":"세 번째"}]}
            """.formatted("a".repeat(32));
    }

    private void assertError(Runnable action, ChatErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ChatBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
