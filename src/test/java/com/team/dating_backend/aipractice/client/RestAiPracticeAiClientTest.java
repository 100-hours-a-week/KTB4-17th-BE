package com.team.dating_backend.aipractice.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RestAiPracticeAiClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private RestAiPracticeAiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/api/v2/practice/start", exchange -> {
            requestBody.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"session_id\":\"ai-session-42\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        server.createContext("/ai/api/v2/practice/ai-session-42/messages", exchange -> {
            byte[] response = replyJson("메시지 응답");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        server.createContext("/ai/api/v2/practice/ai-session-42/retry", exchange -> {
            byte[] response = replyJson("재생성 응답");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        server.createContext("/ai/api/v2/practice/ai-session-42/end", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        AiPracticeProperties properties = new AiPracticeProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new RestAiPracticeAiClient(RestClient.builder(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 세션_시작_요청에_나와_상대_회원_ID를_snake_case로_보낸다() {
        String aiSessionId = client.startSession(42L, 77L);

        assertThat(aiSessionId).isEqualTo("ai-session-42");
        assertThat(requestBody.get())
            .contains("\"me_user_id\":\"42\"")
            .contains("\"partner_user_id\":\"77\"");
    }

    @Test
    void 메시지_재생성_종료_요청에_v2_경로를_사용한다() {
        assertThat(client.sendMessage("ai-session-42", "안녕하세요").content())
            .isEqualTo("메시지 응답");
        assertThat(client.retryMessage("ai-session-42").content())
            .isEqualTo("재생성 응답");

        client.endSession("ai-session-42");
    }

    private byte[] replyJson(String content) {
        return ("{\"session_id\":\"ai-session-42\",\"message_index\":1,\"content\":\""
            + content + "\",\"source\":\"llm\"}").getBytes(StandardCharsets.UTF_8);
    }
}
