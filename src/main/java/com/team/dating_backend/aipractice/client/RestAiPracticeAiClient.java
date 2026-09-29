package com.team.dating_backend.aipractice.client;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationReplyResponse;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.MessageRequest;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.StartSessionRequest;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.StartSessionResponse;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RestAiPracticeAiClient implements AiPracticeAiClient {

    private final RestClient restClient;
    private final AiPracticeProperties properties;

    public RestAiPracticeAiClient(RestClient.Builder restClientBuilder,
        AiPracticeProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @Override
    public String startSession(Long meUserId, Long partnerMemberId) {
        StartSessionResponse response = request()
            .uri(uri(properties.getInitialSessionPath()))
            .body(new StartSessionRequest(
                partnerMemberId == null ? null : partnerMemberId.toString(),
                meUserId == null ? null : meUserId.toString(),
                null))
            .retrieve()
            .body(StartSessionResponse.class);
        if (response == null || response.sessionId() == null
            || response.sessionId().isBlank() || response.sessionId().length() > 100) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        return response.sessionId();
    }

    @Override
    public GenerationReplyResponse sendMessage(String aiSessionId, String userMessage) {
        GenerationReplyResponse response = request()
            .uri(uri(properties.getMessagePath(), aiSessionId))
            .body(new MessageRequest(userMessage))
            .retrieve()
            .body(GenerationReplyResponse.class);
        return validateReply(response, aiSessionId);
    }

    @Override
    public GenerationReplyResponse retryMessage(String aiSessionId) {
        GenerationReplyResponse response = request()
            .uri(uri(properties.getRetryPath(), aiSessionId))
            .retrieve()
            .body(GenerationReplyResponse.class);
        return validateReply(response, aiSessionId);
    }

    @Override
    public void endSession(String aiSessionId) {
        request()
            .uri(uri(properties.getEndSessionPath(), aiSessionId))
            .retrieve()
            .toBodilessEntity();
    }

    private GenerationReplyResponse validateReply(
        GenerationReplyResponse response, String expectedAiSessionId) {
        if (response == null || response.sessionId() == null
            || !response.sessionId().equals(expectedAiSessionId)
            || response.content() == null || response.content().isBlank()
            || response.content().length() > 10_000
            || response.messageIndex() == null || response.messageIndex() < 0
            || !"llm".equals(response.source()) && !"fallback".equals(response.source())) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        return response;
    }

    private RestClient.RequestBodyUriSpec request() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_NOT_CONFIGURED);
        }

        RestClient.RequestBodyUriSpec request = restClient.post();
        request.contentType(MediaType.APPLICATION_JSON);
        request.accept(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            request.header("X-API-Key", properties.getApiKey());
        }
        return request;
    }

    private URI uri(String path) {
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String relativePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + relativePath);
    }

    private URI uri(String path, String aiSessionId) {
        if (path == null || path.isBlank()) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String pathTemplate = path.replace("%s", "{aiSessionId}");
        String relativePath = pathTemplate.startsWith("/") ? pathTemplate : "/" + pathTemplate;
        return UriComponentsBuilder.fromUriString(baseUrl + relativePath)
            .buildAndExpand(aiSessionId)
            .encode()
            .toUri();
    }
}
