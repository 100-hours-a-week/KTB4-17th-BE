package com.team.dating_backend.aisimulation.client;

import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RestAiSimulationAiClient implements AiSimulationAiClient {

    private final RestClient restClient;
    private final AiSimulationProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public RestAiSimulationAiClient(
        RestClient.Builder restClientBuilder,
        AiSimulationProperties properties,
        ObjectMapper objectMapper) {
        this(createRestClient(restClientBuilder, properties), properties, objectMapper);
    }

    private static RestClient createRestClient(
        RestClient.Builder restClientBuilder,
        AiSimulationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    RestAiSimulationAiClient(
        RestClient restClient,
        AiSimulationProperties properties,
        ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public SimulationResponse run(SimulationRequest request) {
        try {
            return restClient.post()
                .uri(uri())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(SimulationResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (RestClientException exception) {
            throw new AiSimulationBusinessException(
                isConversionFailure(exception)
                    ? AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID
                    : AiSimulationErrorCode.AI_SERVER_UNAVAILABLE,
                exception);
        }
    }

    private URI uri() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
            || properties.getSimulationPath() == null
            || properties.getSimulationPath().isBlank()) {
            throw new AiSimulationBusinessException(
                AiSimulationErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String path = properties.getSimulationPath().startsWith("/")
            ? properties.getSimulationPath()
            : "/" + properties.getSimulationPath();
        return URI.create(baseUrl + path);
    }

    private AiSimulationBusinessException mapResponseException(
        RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String detail = detailText(exception.getResponseBodyAsString())
            .toLowerCase(Locale.ROOT);

        AiSimulationErrorCode errorCode;
        if (status == 404) {
            errorCode = detail.startsWith("me:")
                ? AiSimulationErrorCode.ME_PERSONA_NOT_FOUND
                : AiSimulationErrorCode.TARGET_PERSONA_NOT_FOUND;
        } else if (status == 409) {
            errorCode = AiSimulationErrorCode.SIMULATION_ALREADY_RUNNING;
        } else if (status == 422) {
            errorCode = AiSimulationErrorCode.AI_REQUEST_REJECTED;
        } else if (status == 503) {
            errorCode = AiSimulationErrorCode.SIMULATION_GENERATION_FAILED;
        } else if (status >= 500) {
            errorCode = AiSimulationErrorCode.AI_SERVER_UNAVAILABLE;
        } else {
            errorCode = AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID;
        }
        return new AiSimulationBusinessException(errorCode, exception);
    }

    private String detailText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode detail = objectMapper.readTree(body).get("detail");
            if (detail == null) {
                return body;
            }
            if (detail.isTextual()) {
                return detail.asText();
            }
            if (detail.isObject() && detail.hasNonNull("message")) {
                return detail.get("message").asText();
            }
            return detail.toString();
        } catch (Exception ignored) {
            return body;
        }
    }

    private boolean isConversionFailure(Throwable throwable) {
        if (throwable.getMessage() != null
            && throwable.getMessage().contains("extracting response")) {
            return true;
        }
        Throwable cause = throwable;
        while (cause != null) {
            String name = cause.getClass().getName();
            if (name.contains("Json") || name.contains("MessageConversion")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
