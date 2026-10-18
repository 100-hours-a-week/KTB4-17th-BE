package com.team.dating_backend.chat.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.chat.config.ChatDelegationProperties;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
