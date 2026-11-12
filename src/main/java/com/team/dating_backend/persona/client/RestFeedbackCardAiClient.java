package com.team.dating_backend.persona.client;

import com.team.dating_backend.persona.config.PersonaAiProperties;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerRequest;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerResponse;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.CardResponse;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.NextRequest;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RestFeedbackCardAiClient implements FeedbackCardAiClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final PersonaAiProperties properties;
    private final ObjectMapper objectMapper;

    public RestFeedbackCardAiClient(
        RestClient.Builder restClientBuilder,
        PersonaAiProperties properties,
        ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public CardResponse next(String userId) {
        try {
            ResponseEntity<CardResponse> response = request(properties.getFeedbackCardsNextPath())
                .body(new NextRequest(userId))
                .retrieve()
                .toEntity(CardResponse.class);
            if (response.getStatusCode().value() == 204) {
                return null;
            }
            if (response.getBody() == null) {
                throw new PersonaBusinessException(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID);
            }
            return response.getBody();
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (RestClientException exception) {
            throw mapClientException(exception);
        }
    }

    @Override
    public AnswerResponse answer(String cardId, AnswerRequest body) {
        String path = path(properties.getFeedbackCardsAnswerPath(), cardId);
        try {
            return request(path)
                .body(body)
                .retrieve()
                .body(AnswerResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (RestClientException exception) {
            throw mapClientException(exception);
        }
    }

    private RestClient.RequestBodySpec request(String path) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
            || properties.getInternalApiKey() == null || properties.getInternalApiKey().isBlank()) {
            throw new PersonaBusinessException(PersonaErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        return restClient.post()
            .uri(uri(path))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(INTERNAL_API_KEY_HEADER, properties.getInternalApiKey());
    }

    private URI uri(String path) {
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String relativePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + relativePath);
    }

    private String path(String template, String cardId) {
        String pathTemplate = template.replace("%s", "{cardId}");
        return UriComponentsBuilder.fromPath(pathTemplate)
            .buildAndExpand(cardId)
            .encode()
            .toUriString();
    }

    private PersonaBusinessException mapResponseException(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String detail = detailText(exception.getResponseBodyAsString()).toLowerCase(Locale.ROOT);
        String code = detailCode(exception.getResponseBodyAsString());

        PersonaErrorCode errorCode;
        if (status == 403 || status == 404) {
            errorCode = PersonaErrorCode.FEEDBACK_CARD_NOT_AVAILABLE;
        } else if (status == 409) {
            errorCode = code.contains("expired") || detail.contains("expired")
                ? PersonaErrorCode.FEEDBACK_CARD_EXPIRED
                : PersonaErrorCode.FEEDBACK_CARD_ALREADY_ANSWERED;
        } else if (status == 422) {
            errorCode = PersonaErrorCode.AI_REQUEST_REJECTED;
        } else if (status == 401 || status == 429 || status >= 500) {
            errorCode = PersonaErrorCode.AI_SERVER_UNAVAILABLE;
        } else {
            errorCode = PersonaErrorCode.AI_SERVER_RESPONSE_INVALID;
        }
        return new PersonaBusinessException(errorCode, exception);
    }

    private PersonaBusinessException mapClientException(RestClientException exception) {
        boolean conversionFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getName();
            conversionFailure |= name.startsWith("tools.jackson.")
                || name.contains("MessageConversion");
        }
        return new PersonaBusinessException(
            conversionFailure
                ? PersonaErrorCode.AI_SERVER_RESPONSE_INVALID
                : PersonaErrorCode.AI_SERVER_UNAVAILABLE,
            exception);
    }

    private String detailCode(String body) {
        JsonNode detail = detailNode(body);
        return detail != null && detail.isObject() && detail.hasNonNull("code")
            ? detail.get("code").asText().toLowerCase(Locale.ROOT)
            : "";
    }

    private String detailText(String body) {
        JsonNode detail = detailNode(body);
        if (detail == null) {
            return body == null ? "" : body;
        }
        if (detail.isTextual()) {
            return detail.asText();
        }
        if (detail.isObject() && detail.hasNonNull("message")) {
            return detail.get("message").asText();
        }
        return detail.toString();
    }

    private JsonNode detailNode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body).get("detail");
        } catch (Exception ignored) {
            return null;
        }
    }
}
