package com.team.dating_backend.chat.client;

import com.team.dating_backend.chat.config.ChatDelegationProperties;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Turn;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Component
public class RestChatDelegationAiClient implements ChatDelegationAiClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String DRAFTS_PATH = "/ai/api/v1/chat_end/drafts";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int DRAFT_TURN_COUNT = 3;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final RestClient restClient;
    private final ChatDelegationProperties properties;

    @Autowired
    public RestChatDelegationAiClient(
        RestClient.Builder builder, ChatDelegationProperties properties) {
        this(createRestClient(builder), properties);
    }

    RestChatDelegationAiClient(RestClient restClient, ChatDelegationProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient createRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return builder.requestFactory(factory).build();
    }

    @Override
    public DraftResponse createDraft(DraftRequest request) {
        requireConfiguration();
        try {
            DraftResponse response = restClient.post()
                .uri(uri())
                .header(INTERNAL_API_KEY_HEADER, properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(DraftResponse.class);
            validate(request, response);
            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is5xxServerError()) {
                throw failure(ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
            }
            throw failure(exception.getStatusCode().is4xxClientError()
                ? ChatErrorCode.CHAT_DELEGATION_AI_REQUEST_REJECTED
                : ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        } catch (ResourceAccessException exception) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        }
    }

    private void validate(DraftRequest request, DraftResponse response) {
        if (response == null
            || !request.roomId().equals(response.roomId())
            || response.draftId() == null
            || !response.draftId().matches("[0-9a-f]{32}")
            || !"PENDING".equals(response.status())
            || response.turns() == null
            || response.turns().size() != DRAFT_TURN_COUNT) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        }
        for (int index = 0; index < DRAFT_TURN_COUNT; index++) {
            Turn turn = response.turns().get(index);
            if (turn == null || turn.turn() == null || turn.turn() != index + 1
                || turn.message() == null || turn.message().isBlank()
                || turn.message().length() > MAX_MESSAGE_LENGTH) {
                throw failure(ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
            }
        }
    }

    private void requireConfiguration() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
            || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_AI_NOT_CONFIGURED);
        }
    }

    private URI uri() {
        try {
            String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
            URI uri = URI.create(baseUrl + DRAFTS_PATH);
            if (uri.getHost() == null || !("http".equals(uri.getScheme())
                || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_AI_NOT_CONFIGURED);
        }
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }
}
