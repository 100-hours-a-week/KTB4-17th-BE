package com.team.dating_backend.aipractice.client;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.ContinueGenerationRequest;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationAcceptedResponse;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.InitialGenerationRequest;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RestAiPracticeAiClient implements AiPracticeAiClient {

    private final RestClient restClient;
    private final AiPracticeProperties properties;

    public RestAiPracticeAiClient(RestClient.Builder restClientBuilder,
        AiPracticeProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public String startGeneration(InitialGenerationRequest request, String idempotencyKey) {
        GenerationAcceptedResponse response = request()
            .uri(uri(properties.getInitialSessionPath()))
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(GenerationAcceptedResponse.class);
        if (response == null || response.aiSessionId() == null
            || response.aiSessionId().isBlank() || response.aiSessionId().length() > 100) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        return response.aiSessionId();
    }

    @Override
    public void continueGeneration(
        String aiSessionId, ContinueGenerationRequest request, String idempotencyKey) {
        request()
            .uri(uri(properties.getMessagePath(), aiSessionId))
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void endSession(String aiSessionId, String idempotencyKey) {
        request()
            .uri(uri(properties.getEndSessionPath(), aiSessionId))
            .header("Idempotency-Key", idempotencyKey)
            .retrieve()
            .toBodilessEntity();
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
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String pathTemplate = path.replace("%s", "{aiSessionId}");
        String relativePath = pathTemplate.startsWith("/") ? pathTemplate : "/" + pathTemplate;
        return UriComponentsBuilder.fromUriString(baseUrl + relativePath)
            .buildAndExpand(aiSessionId)
            .encode()
            .toUri();
    }
}
